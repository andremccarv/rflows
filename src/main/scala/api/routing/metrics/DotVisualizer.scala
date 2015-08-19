package api.routing.metrics

import java.util.concurrent.TimeUnit

import api.routing.dsl._
import com.codahale._
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.{ ConfigFactory, Config }

import scala.concurrent.duration.Duration
import scala.util.Try

/**
  * Created by dkondratiuk on 6/12/15.
  */
trait DotVisualizer[Ctx] extends RoutingDSLBase[Ctx] with SimpleDotVisualizer {

  private def deCompose[In, Out](f: Flow[In, Out], flows: List[Flow[_, _]] = Nil): List[Flow[Any, Any]] = f match {
    case c: Compose[Any, Any, Any] @unchecked => deCompose(c.do1) ++ deCompose(c.do2)
    case f: Flow[_, _]                        => List(f.asInstanceOf[Flow[Any, Any]])
  }

  def getEdges[In, Out](f: Flow[In, Out], ignore: Option[String] = None): List[(String, String)] = f match { //TODO implement Split -> Act -> Act -> Aggregate pattern or disable such aggregation
    case TaggedFlow(f) => getEdges(f)
    case c: Compose[In, Any, Out] @unchecked =>
      val pipe = deCompose(c)
      if (ignore.nonEmpty && pipe.map(_.name).contains(ignore.get)) Nil else
        pipe.zip(pipe.tail) flatMap {
          case (a: Split[Any, Any, Any, Group#Tag, Group] @unchecked, b: Aggregate[Any, Any] @unchecked) =>
            visualizeSplitAggregate(a, Nil, b)
          case (a: Flow[Any, Any] @unchecked, b: Flow[Any, Any] @unchecked) => List(a.name -> b.name)
        }
    case _ => Nil
  }

  private def visualizeSplitAggregate(
    in: Split[Any, Any, Any, Group#Tag, Group],
    between: List[Flow[Any, Any]],
    out: Aggregate[Any, Any]): List[(String, String)] = in.g.registry flatMap { l =>

    val decomposed = deCompose(l)
    in.name -> decomposed.head.name :: getEdges(l, Some(in.name)) ::: List(decomposed.last.name -> out.name)
  }

  def getSimpleDotGraphF[In, Out](f: Flow[In, Out], stats: List[Stat] = Nil): String = getSimpleDotGraph[In, Out](getEdges(f), stats)

}

trait SimpleDotVisualizer {
  case class Stat(name: String, property: String, value: String)

  def getSimpleDotGraph[In, Out](edges: List[(String, String)], stats: List[Stat] = Nil): String =
    s"""
       |digraph flow  {
       |  rankdir=LR
       |  node [shape=rect]
       |  ${
      stats.groupBy(_.name) map {
        case (k, v) =>
          val statsText = v.map(x => x.property + " = " + x.value).mkString("<BR/>")
          s"""$k[label = <$k <BR/> <FONT POINT-SIZE="10">$statsText</FONT> >]"""
      } mkString ("\n")
    }
        |${edges.map(x => "  " + x._1 + " -> " + x._2).mkString("\n")}
        |}
     """.stripMargin
}

trait EdgesProvider[Ctx] extends DotVisualizer[Ctx] with FlowRegistration[Ctx] {

  def aggregator: MetricsAggregator = DefaultMetricsAggregator

  override def registerCompositeFlow[In, Out](f: Flow[In, Out]) = {
    aggregator.register(getEdges(f))
    super.registerCompositeFlow(f)
  }

}

/**
  * Not thread-safe, which is fine if you use it only from reporter
  */
trait MetricsAggregator {

  type ActName = String
  type PropertyName = String
  type StatName = String

  private var _metrics = Map.empty[(StatName, ActName, PropertyName), Double]

  private var _edges = Set.empty[(ActName, ActName)]

  protected def metrics = _metrics

  def edges = _edges

  def put(sn: StatName, an: ActName, propertyName: PropertyName, value: Double): Unit = if ((edges.map(_._1) ++ edges.map(_._2)).contains(an)) {
    _metrics += (sn, an, propertyName) -> value
  }

  def register(edges: List[(ActName, ActName)]) = {
    _edges ++= edges.toSet
  }

  def metricNames = _metrics.keys.map(_._3)

}

trait MetricsReporter {

  def aggregator: MetricsAggregator = DefaultMetricsAggregator

  def reportLocal(sn: String, name: String, value: Double) = {
    val nm = TaggedName.fromRaw(name)
    for (flowName <- nm.tags.get("flowName")) {
      aggregator.put(sn, flowName, nm.name, value / 1000000)
    }
  }

  implicit def metricRegistry: metrics.MetricRegistry

  def config: Config

  import java.util.SortedMap
  import scala.collection.JavaConverters._

  lazy val localReporter = new metrics.ScheduledReporter(metricRegistry,
    "LocalReporter",
    metrics.MetricFilter.ALL,
    TimeUnit.MICROSECONDS,
    TimeUnit.MICROSECONDS) {

    override def report(
      gauges: SortedMap[String, metrics.Gauge[_]],
      counters: SortedMap[String, metrics.Counter],
      histograms: SortedMap[String, metrics.Histogram],
      meters: SortedMap[String, metrics.Meter],
      timers: SortedMap[String, metrics.Timer]) = {

      timers.asScala.foreach {
        case (name, timer) =>

          reportLocal("99th", name, timer.getSnapshot.get99thPercentile)
          reportLocal("98th", name, timer.getSnapshot.get98thPercentile)
          reportLocal("95th", name, timer.getSnapshot.get95thPercentile)
          reportLocal("75th", name, timer.getSnapshot.get75thPercentile)
          reportLocal("999th", name, timer.getSnapshot.get999thPercentile)
          reportLocal("Max", name, timer.getSnapshot.getMax)
          reportLocal("Min", name, timer.getSnapshot.getMin)
          reportLocal("Mean", name, timer.getSnapshot.getMean)
          reportLocal("Median", name, timer.getSnapshot.getMedian)
      }
    }
  }

}

object DefaultMetricsReporter extends MetricsReporter {
  override implicit def metricRegistry: MetricRegistry = AppMetrics.metricRegistry

  // $COVERAGE-OFF$
  override def config: Config = ConfigFactory.load()
  // $COVERAGE-ON$

}

trait MetricsDotVisualizer extends MetricsAggregator with SimpleDotVisualizer {

  def getGraph(sn: String): String = getSimpleDotGraph(edges.toList, metrics.filterKeys(_._1 == sn).map{ case (k, v) => Stat(k._1, k._2, v.toString + " ms") }.toList)

}

object DefaultMetricsAggregator extends MetricsAggregator with MetricsDotVisualizer
