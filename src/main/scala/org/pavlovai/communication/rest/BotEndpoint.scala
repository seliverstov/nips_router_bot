package org.pavlovai.communication.rest

import java.time.{Clock, Instant}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import info.mukel.telegrambot4s.models.{Chat, ChatType, Message, Update}
import org.pavlovai.dialog.Dialog
import org.pavlovai.dialog.DialogFather.UserAvailable
import org.pavlovai.communication.{Bot, Endpoint}
import spray.json._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Random, Try}

/**
  * @author vadim
  * @since 10.07.17
  */
class BotEndpoint(daddy: ActorRef, clock: Clock) extends Actor with ActorLogging {
  import BotEndpoint._
  import Dialog._

  private val rnd: Random = Random

  private val botsQueues: Map[String, mutable.Queue[Update]] =
    Try(context.system.settings.config.getStringList("bot.registered").asScala).getOrElse(Seq.empty)
      .map { token =>
        daddy ! UserAvailable(Bot(token))
        token -> mutable.Queue.empty[Update]
      }.toMap

  private val activeChats: mutable.Map[(Bot, Long), ActorRef] = mutable.Map.empty[(Bot, Long), ActorRef]

  override def receive: Receive = {
    case GetMessages(token) =>
      sender ! botsQueues.get(token).fold[Any] {
        log.warning("bot {} not registered", token)
        akka.actor.Status.Failure(new IllegalArgumentException("bot not registered"))
      } { mq =>
        val res = mq.toList
        mq.clear()
        res
      }

    case SendMessage(token, chat, m: TalkEvaluationMessage) =>
      activeChats.get(Bot(token) -> chat).foreach(_ ! Dialog.EndDialog(Some(Bot(token))))
      sender ! Message(rnd.nextInt(), None, Instant.now(clock).getNano, Chat(chat, ChatType.Private), text = Some(m.toJson(talkEvaluationFormat).toString))

    case SendMessage(token, chat, m: BotMessage) =>
      //TODO slowdown robots
      activeChats.get(Bot(token) -> chat).foreach(_ ! Dialog.PushMessageToTalk(Bot(token), m.text))
      sender ! Message(rnd.nextInt(), None, Instant.now(clock).getNano, Chat(chat, ChatType.Private), text = Some(m.toJson(botMessageFormat).toString))

    case Endpoint.DeliverMessageToUser(Bot(token), text, Some(dialogId)) =>
      botsQueues.get(token).fold[Any] {
        log.warning("bot {} not registered", token)
        akka.actor.Status.Failure(new IllegalArgumentException("bot not registered"))
      }(_ += Update(0, Some(Message(0, None, Instant.now(clock).getNano, Chat(dialogId, ChatType.Private), text = Some(text)))) )

    case Endpoint.ActivateTalkForUser(user: Bot, talk) => activeChats.put(user -> talk.chatId, talk)

    case Endpoint.FinishTalkForUser(user: Bot, talk) => activeChats.remove(user -> talk.chatId)
  }
}

object BotEndpoint extends SprayJsonSupport with DefaultJsonProtocol  {
  def props(talkConstructor: ActorRef, clock: Clock = Clock.systemDefaultZone()): Props = Props(new BotEndpoint(talkConstructor, clock))

  sealed trait BotMessage {
    val text: String
  }
  case class TextMessage(text: String) extends BotMessage
  case class TextWithEvaluationMessage(text: String, evaluation: Int) extends BotMessage
  case class SummaryEvaluation(quality: Int, breadth: Int, engagement: Int)
  case class TalkEvaluationMessage(evaluation: SummaryEvaluation) extends BotMessage {
    val text = ""
  }

  case class SendMessage(token: String, chat_id: Long, text: BotMessage)
  case class GetMessages(token: String)

  implicit val normalMessageFormat: JsonFormat[TextWithEvaluationMessage] = jsonFormat2(TextWithEvaluationMessage)
  implicit val summaryEvaluationFormat: JsonFormat[SummaryEvaluation] = jsonFormat3(SummaryEvaluation)
  implicit val talkEvaluationFormat: JsonFormat[TalkEvaluationMessage] = jsonFormat(TalkEvaluationMessage.apply _, "evaluation")
  implicit val firstMessageFormat: JsonFormat[TextMessage] = jsonFormat1(TextMessage)
  implicit val botMessageFormat = new JsonFormat[BotMessage] {
    override def write(obj: BotMessage): JsValue = obj match {
      case TextMessage(text: String) => JsObject("text" -> text.toJson)
      case TextWithEvaluationMessage(text: String, evaluation: Int) => JsObject("text" -> text.toJson, "evaluation" -> evaluation.toJson)
      case TalkEvaluationMessage(SummaryEvaluation(quality, breadth, engagment)) => JsObject("quality" -> quality.toJson, "breadth" -> breadth.toJson, "engagement" -> engagment.toJson)
    }

    override def read(json: JsValue): BotMessage = json.asJsObject.getFields("text") match {
      case Seq(JsString("/end")) => json.convertTo[TalkEvaluationMessage]
      case _ if json.asJsObject.fields.contains("evaluation") => json.convertTo[TextWithEvaluationMessage]
      case _ if !json.asJsObject.fields.contains("evaluation") => json.convertTo[TextMessage]
      case _ => serializationError(s"Invalid json format: $json")
    }
  }

  implicit val sendMessageFormat: JsonFormat[SendMessage] = jsonFormat3(SendMessage)
}
