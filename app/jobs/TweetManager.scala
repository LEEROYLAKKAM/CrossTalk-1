package jobs

import akka.actor.Actor
import play.api.Play
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer
import akka.actor.ActorRef
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import models._
import akka.actor.Cancellable
import akka.actor.ActorSystem
import akka.actor.Props
import TweetManager._
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import play.api.libs.concurrent.Execution.Implicits._
import utils.AkkaImplicits._
import scala.util.Random
import scala.language.postfixOps

/* TODO: cover cases for the Streaming API, if doable */
/**
 * Manage all the tweets streamer to return from the two APIs, and ensure that the quotas are enforced.
 * Since we want a dynamic update, the receive method can execute various queries :
 *  1		Start multiple queries
 *  2		Stop all the queries
 *
 *  Note that there should be only one instance of the manager per application.
 */
object TweetManager {

  /* Nested class to enforce singleton */
  class TweetManager extends Actor {

    val threshold = 60 * 60 / 450 / 4 /* seconds, see https://dev.twitter.com/docs/rate-limiting/1.1/limits */

    /** A list of running and cancellables, along with their reference actors */
    var cancellables: List[Cancellable] = Nil
    /** A list of running and cancellable Streamers, along with their reference actors */
    var streams: List[Cancellable] = Nil
    
    /** A list of queries to start. Only used prior to the start of the Manager. */
    var queriesToStart: List[(TweetQuery, ActorRef)] = Nil


    def receive = {

      case AddQueries(queries) =>
        assert(cancellables.isEmpty, "Cannot add more queries without cancelling the ones running.")
        queriesToStart ++= queries
        
      case Start =>
        assert(cancellables.isEmpty, "Cannot restart queries again without cancelling the ones running.")

        val keysSet = queriesToStart.map(qu => qu._1.keywords).toSet
        val checkerRef = toRef(Props(new TweetDuplicateChecker(queriesToStart.size*100 / keysSet.size, keysSet)))

        val searchRate = threshold * queriesToStart.size
        var startTime = 0
        cancellables = queriesToStart.shuffle flatMap { qur =>
          val searcherRef = toRef(Props(new TweetSearcher(qur._1, qur._2, checkerRef)))
          /* val streamerRef = toRef(Props(new TweetStreamer(qur._1, qur._2))) */
          val cancellable1 = searcherRef.schedule(startTime, searchRate, TimeUnit.SECONDS, Ping)
          /* val cancellable2 = streamerRef.schedule(startTime, searchRate, TimeUnit.SECONDS, Ping) */
          startTime += threshold
          cancellable1 /* :: cancellable2 */ :: Nil
        }
        cancellables :+= checkerRef.schedule(searchRate, searchRate, TimeUnit.SECONDS, Cleanup) /* Schedule the duplicate checker */
        queriesToStart = Nil /* Reset the queries to start; all are started! */

      case Stop =>
        cancellables foreach (_.cancel)
        cancellables = Nil

      case _ => sys.error("Wrong message sent to the TweetManager")
    }
  }

  val TweetManagerRef = toRef(Props(new TweetManager()))

  /**
   * Return an OAuth consumer with the keys ready for the Twitter API.
   */
  val consumer = {
    /* Access token, saved as Play Configuration Parameters */
    val consumerKey = Play.current.configuration.getString("twitter.k2.consumerKey").getOrElse(sys.error("No consumer key found in conf."))
    val consumerSecret = Play.current.configuration.getString("twitter.k2.consumerSecret").getOrElse(sys.error("No consumer secret found in conf."))
    val accessToken = Play.current.configuration.getString("twitter.k2.accessToken").getOrElse(sys.error("No access token found in conf."))
    val accessTokenSecret = Play.current.configuration.getString("twitter.k2.accessTokenSecret").getOrElse(sys.error("No access token secret found in conf."))

    val consumer = new CommonsHttpOAuthConsumer(consumerKey, consumerSecret)
    consumer.setTokenWithSecret(accessToken, accessTokenSecret)

    consumer
  }

  implicit class RichList[T](lst: List[T]) {
    def shuffle: List[T] = {
          val rds = new Random
      lst.map(entry => (entry, rds.nextInt)).sortBy(x => x._2).map(entry => entry._1)
    }
  }
}
