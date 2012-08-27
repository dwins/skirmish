package skirmish

import org.{ pircbotx => pb }
import java.net.URLEncoder.encode
import akka.actor._, akka.pattern._

object Bot {
  val system = ActorSystem("Skirmish")
  type Bot = pb.PircBotX

  val Greet = "hi wolsni.*".r
  val Proj = "proj (.*)".r
  val EvanResponse = "evanresponse.*".r
  val NewEvanResponse = "tell evan (.*)".r

  implicit val defaultTimeout = akka.util.Timeout.never

  val logic = (ref: ActorRef) =>
    new pb.hooks.ListenerAdapter[Bot] with pb.hooks.Listener[Bot] {
      override def onMessage(event: pb.hooks.events.MessageEvent[Bot]) {
        event.getMessage match {
          case Greet() => event.respond("Hey there!")
          case Proj(query) => event.respond("http://prj2epsg.org/search?terms=" + encode(query, "UTF-8"))
          case EvanResponse() => 
            ref ! AskForMessage(event.getBot, event.getChannel)
            for (Message(msg) <- ask(ref, AskForMessage))
              event.getBot().sendMessage(event.getChannel, msg)
          case x => // ignore.
        }
      }

      override def onPrivateMessage(event: pb.hooks.events.PrivateMessageEvent[Bot]) {
        event.getMessage match {
          case NewEvanResponse(response) =>
            ref ! AddNewMessage(response)
            event.respond("Yeah, sure. I'll tell him.")
          case _ => event.respond("I don't get it.")
        }
      }
    }

  def main(args: Array[String]) {
    val bot = new pb.PircBotX
    val evanResponses = system.actorOf(Props[EvanResponder], name="evanresponder")
    bot.getListenerManager().addListener(logic(evanResponses))
    bot.setName("wolsni")
    bot.connect("irc.freenode.net")
    bot.joinChannel("#opengeo-fp")
  }
}

case class AddNewMessage(msg: String)
case class AskForMessage(bot: pb.PircBotX, channel: pb.Channel)
case class BulkAddMessages(msgs: Seq[String])
case class Loaded(msgs: IndexedSeq[String])
case class Message(msg: String)
case class Recorded(msgs: Seq[String])
case object LoadMessages

class FileHandler extends Actor {
  val store: java.io.File = new java.io.File("evanmessages")

  def receive = {
    case BulkAddMessages(msgs) => 
      appendAll(msgs)
      sender ! Recorded(msgs)
    case LoadMessages =>
      try 
        sender ! Loaded(readAll)
      catch {
        case (ex: java.io.FileNotFoundException) =>
          // Silently eat this one - if we don't have the file then we haven't
          // recorded any messages for Evan yet.
      }
  }

  def appendAll(msgs: Seq[String]) =
    closing(new java.io.PrintWriter(new java.io.FileWriter(store, true))) { f =>
      msgs.foreach(f.println(_))
    }

  def readAll: IndexedSeq[String] = 
   closing(new java.io.BufferedReader(new java.io.FileReader(store))) { r =>
     Iterator
       .continually(r.readLine())
       .takeWhile(null !=)
       .toIndexedSeq
   }

  def closing[C <: java.io.Closeable, T](c: C)(f: C => T): T = 
    try
      f(c)
    finally
      c.close()
}

class EvanResponder extends Actor {
  import scala.util.Random

  var messages = IndexedSeq.empty[String]

  var pendingMessages = Nil: Seq[String]

  var recordingInFlight: Boolean = false

  val fileHandler = context.actorOf(Props[FileHandler], name="filehandler")

  fileHandler ! LoadMessages

  def receive = {
    case AskForMessage(bot, channel) => 
      randomMember(messages) foreach { bot.sendMessage(channel, _) } 
    case Loaded(msgs) =>
      messages = msgs
    case AddNewMessage(msg) =>
      if (recordingInFlight) {
        pendingMessages +:= msg
      } else {
        recordingInFlight = true
        fileHandler ! BulkAddMessages(Seq(msg))
      }
    case Recorded(msgs) =>
      messages ++= msgs
      if (pendingMessages.isEmpty) {
        recordingInFlight = false
      } else {
        fileHandler ! BulkAddMessages(pendingMessages)
        pendingMessages = Nil
      }
    case x => println("Unhandled message: " + x)
  }

  def randomMember[A](xs: IndexedSeq[A]): Option[A] =
    if (xs isEmpty)
      None
    else 
      Some(xs(Random.nextInt(xs.size)))
}
