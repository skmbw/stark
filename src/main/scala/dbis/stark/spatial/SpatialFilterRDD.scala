package dbis.stark.spatial

import dbis.stark.{Interval, STObject}
import dbis.stark.spatial.JoinPredicate.JoinPredicate
import dbis.stark.spatial.indexed.{IntervalTree1, RTree}
import dbis.stark.spatial.partitioner.{SpatialPartition, SpatialPartitioner}
import org.apache.spark.annotation.DeveloperApi
import dbis.stark.spatial.IndexTyp.IndexTyp
import org.apache.spark.rdd.RDD
import org.apache.spark.{Partition, Partitioner, TaskContext}

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

object IndexTyp extends Enumeration {
  type IndexTyp = Value
  val NONE, SPATIAL, TEMPORAL = Value
}


/**
  * A stpatio-temporal filter implementation
  *
  * @param parent The parent RDD serving as input
  * @param qry The query object used in the filter evaluation
  * @param predicateFunc The predicate to apply in the filter
  * @param treeOrder The (optional) order of the tree. <= 0 to not apply indexing
  * @param checkParties Perform partition check
  * @tparam G The type representing spatio-temporal data
  * @tparam V The payload data
  */
class SpatialFilterRDD[G <: STObject : ClassTag, V : ClassTag] private (
  private val parent: RDD[(G,V)],
  qry: G,
  predicate: JoinPredicate,
  predicateFunc: (G,G) => Boolean,
  indexType: IndexTyp,
  treeOrder: Int,
  private val checkParties: Boolean) extends RDD[(G,V)](parent) {

  require(indexType != IndexTyp.SPATIAL || treeOrder > 0)

  def this(parent: RDD[(G,V)], qry: G, predicateFunc: (G,G) => Boolean,indexType: IndexTyp) =
    this(parent, qry,null, predicateFunc,indexType, -1, false)

  def this(parent: RDD[(G,V)], qry: G, predicate: JoinPredicate,indexType: IndexTyp, treeOrder: Int = -1) =
    this(parent, qry, predicate,JoinPredicate.predicateFunction(predicate), indexType,treeOrder, true)

  /**
    * The partitioner of this RDD.
    *
    * This will always be set to the parent's partitioner
    */
  override val partitioner: Option[Partitioner] = parent.partitioner

  /**
    * Return the partitions that have to be processed.
    *
    * We apply partition pruning in this step: If a [[dbis.stark.spatial.partitioner.SpatialPartitioner]]
    * is present, we check if the partition can contain candidates. If not, it is not returned
    * here.
    *
    *
    * @return The list of partitions of this RDD
    */
  override def getPartitions: Array[Partition] = partitioner.map{
      // check if this is a spatial partitioner
      case sp: SpatialPartitioner if checkParties =>

        val spatialParts = ListBuffer.empty[Partition]

        val qryEnv = qry.getGeo.getEnvelopeInternal
        var parentPartiId = 0
        var spatialPartId = 0
        val numParentParts = parent.getNumPartitions

        // loop over all partitions in the parent
        while (parentPartiId < numParentParts) {

          val cell = sp.partitionBounds(parentPartiId)

          // check if partitions intersect
          if (Utils.toEnvelope(cell.extent).intersects(qryEnv)) {
            // create a new "spatial partition" pointing to the parent partition
            spatialParts += SpatialPartition(spatialPartId, parentPartiId, parent)
            spatialPartId += 1
          }

          parentPartiId += 1
        }

        spatialParts.toArray
      case tp: TemporalPartitioner =>

        val spatialParts = ListBuffer.empty[Partition]

        val qryEnv = qry.getGeo.getEnvelopeInternal
        var i = 0
        var cnt = 0
        val numParentParts = parent.getNumPartitions
        while (i < numParentParts) {
          predicate match {
            case JoinPredicate.INTERSECTS =>
              if (tp.partitionBounds(i).intersects(qry.getTemp.get)) {
                spatialParts += SpatialPartition(cnt, i, parent)
                cnt += 1
              }
            case JoinPredicate.CONTAINEDBY =>
              val newi = {
                if (i == tp.numPartitions - 1) {
                  tp.partitionBounds(i)
                } else {
                  Interval(tp.partitionBounds(i).start, tp.partitionBounds(i + 1).start)
                }
              }
              if (newi.intersects(qry.getTemp.get)) {
                spatialParts += SpatialPartition(cnt, i, parent)
                cnt += 1
              }
            case JoinPredicate.CONTAINS =>
              //println(tp.partitionBounds(i).contains(qry.getTemp.get))
              if (tp.partitionBounds(i).contains(qry.getTemp.get)) {
                spatialParts += SpatialPartition(cnt, i, parent)
                cnt += 1
              }
            case _ =>
              spatialParts += SpatialPartition(cnt, i, parent)
              cnt += 1
          }
          i += 1

        }
        spatialParts.toArray
      case _ =>
        parent.partitions

    }.getOrElse(parent.partitions) // no partitioner


  @DeveloperApi
  override def compute(inputSplit: Partition, context: TaskContext): Iterator[(G, V)] = {

    /* determine the split to process. If a spatial partitioner was applied, the actual
     * partition/split is encapsulated
     */
    val split = inputSplit match {
      case sp: SpatialPartition => sp.parentPartition
      case _ => inputSplit
    }


    indexType match {
      case IndexTyp.NONE =>
        parent.iterator(split, context).filter { case (g, _) => predicateFunc(g, qry) }

      case IndexTyp.SPATIAL =>
        val tree = new RTree[G, (G, V)](treeOrder)
        // insert everything into the tree
        parent.iterator(split, context).foreach { case (g, v) => tree.insert(g, (g, v)) }

        // query tree and perform candidates check
        tree.query(qry).filter { case (g, _) => predicateFunc(g, qry) }

      case IndexTyp.TEMPORAL =>
        val indexTree = new IntervalTree1[G, V]()
        // insert everything into the tree
        parent.iterator(split, context).foreach { case (g, v) => indexTree.insert(g, (g, v)) }
        indexTree.query(qry).filter { case (g, _) => predicateFunc(g, qry) }

//      case _ =>
//        parent.iterator(split, context).filter { case (g, _) => predicateFunc(g, qry) }


    }

  }
}
