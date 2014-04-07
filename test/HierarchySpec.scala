import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._
import play.api.test._
import play.api.test.Helpers._
import akka.actor.ActorSystem
import akka.actor.Props
import jobs._
import models.TweetQuery
import models.GeoSquare
import models.Tweet
import play.api.libs.json.Json
import akka.actor.{Actor, ActorRef}
import models._

@RunWith(classOf[JUnitRunner])
class HierarchySpec extends Specification {
 var track = 0L
 val defaultRow = 4
 val defaultCol = 5
 class notify extends Actor {
  def receive = {
    case Report(id, count) =>
      track += count
  }
 }

  implicit def toRef(a: Props): ActorRef = {
    ActorSystem().actorOf(a)
  }
  "Hierarchy Test" should {
    
    "Multiple Queries" in new WithApplication {
      /*GeoPartitionner test*/
      val geoPart: ActorRef = Props(new GeoPartitionner)
     /*Retrieves the count*/ 
     // val retriever: ActorRef = Props(new )
      /*Listens to the query's result*/
      val listeners = (1 to (defaultRow * defaultCol)).map(x => ActorSystem().actorOf(Props(new Counter((x % defaultRow, x % defaultCol), geoPart))))
      /*The query*/
      val query = TweetQuery("Obama" :: Nil, GeoSquare(-129.4, 20.0, -79, 50.6), defaultRow, defaultCol)
      
      val acts = query.subqueries.zipWithIndex.zip(listeners).map{x => 
        ActorSystem().actorOf(Props(new TweetSearcher(x._1._1, x._2)))
      }
        
      acts.foreach(_ ! "start")
      Thread.sleep(20000)
      listeners.foreach(_ ! "report")
      Thread.sleep(1000)
      geoPart ! "Total"
      geoPart ! "Winner"
      geoPart ! "Size"
  
     }
  }
}
