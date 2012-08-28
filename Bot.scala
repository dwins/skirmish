package skirmish

import org.{ pircbotx => pb }
import java.net.URLEncoder.encode
import akka.actor._, akka.pattern._, akka.event._

object Bot {
  val system = ActorSystem("Skirmish")
  type Bot = pb.PircBotX

  val Greet = "hi wolsni.*".r
  val Proj = "proj (.*)".r
  val EvanResponse = "evanresponse.*".r
  val NewEvanResponse = "tell evan (.*)".r

  object Body {
    def unapply(event: pb.hooks.types.GenericMessageEvent[Bot]): Option[String] = 
      Some(event.getMessage())
  }

  object Sender {
    def unapply(event: pb.hooks.types.GenericMessageEvent[Bot]): Option[String] = 
      Some(event.getUser().getNick())
  }

  implicit val defaultTimeout = akka.util.Timeout.never

  val logic = (ref: ActorRef) =>
    new pb.hooks.ListenerAdapter[Bot] with pb.hooks.Listener[Bot] {
      override def onMessage(event: pb.hooks.events.MessageEvent[Bot]) {
        event match {
          case Body(Greet()) =>
            event.respond("Hey there!")
          case Body(Proj(query)) =>
            event.respond("http://prj2epsg.org/search?terms=" + encode(query, "UTF-8"))
          case Body(EvanResponse()) => 
            ref ! AskForMessage(event.getBot, event.getChannel)
          case Sender(name) if name.toLowerCase == "evancc" =>
            import scala.util.Random.nextInt
            if (nextInt(100) == 0) 
              ref ! AskForReply(event)
        } 
      }

      override def onPrivateMessage(event: pb.hooks.events.PrivateMessageEvent[Bot]) {
        event match {
          case Body(NewEvanResponse(response)) =>
            ref ! AddNewMessage(response)
            event.respond("Yeah, sure. I'll tell him.")
          case Body(Greet()) =>
            event.respond("Hey there!")
          case _ =>
            event.respond("I don't understand.")
        }
      }
    }

  def main(args: Array[String]) {
    val bot = new pb.PircBotX
    val evanResponses = system.actorOf(Props[EvanResponder], name="evanresponder")
    bot.getListenerManager().addListener(logic(evanResponses))
    bot.setName("wolsni")
    bot.connect("irc.freenode.net")
    bot.joinChannel("#opengeo")
  }
}

case class AddNewMessage(msg: String)
case class AskForMessage(bot: pb.PircBotX, channel: pb.Channel)
case class AskForReply(event: pb.hooks.events.MessageEvent[pb.PircBotX])
case class Loaded(msgs: IndexedSeq[String])
case class Message(msg: String)
case object LoadMessages

class FileHandler extends Actor {
  val store: java.io.File = new java.io.File("evanmessages")
  val log = Logging(context.system, this)

  def receive = {
    case AddNewMessage(msg) => append(msg)
    case LoadMessages =>
      try 
        sender ! Loaded(readAll)
      catch {
        case (ex: java.io.FileNotFoundException) =>
          log.info("File not found when loading Evan responses; falling back to empty list")
      }
  }

  def append(msg: String) =
    closing(new java.io.PrintWriter(new java.io.FileWriter(store, true))) {
      _ println msg
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
    case AskForReply(event) => 
      randomMember(messages) foreach { event.respond(_) }
    case Loaded(msgs) => messages = msgs
    case msg @ AddNewMessage(_) => fileHandler forward msg
  }

  def randomMember[A](xs: IndexedSeq[A]): Option[A] =
    if (xs isEmpty)
      None
    else 
      Some(xs(Random.nextInt(xs.size)))
}
