/*
** Copyright [2013-2015] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package models.billing

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps
import org.megam.util.Time
import controllers.stack._
import controllers.Constants._
import controllers.funnel.FunnelErrors._
import models._
import models.billing._
import models.cache._
import models.riak._
import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import org.megam.common.riak.{ GSRiak, GunnySack }
import org.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

/**
 * @author rajthilak
 *
 */

case class DiscountsInput(accounts_id: String, bill_type: String, code: String, status: String) {
  val json = "{\"accounts_id\":\"" + accounts_id + "\",\"bill_type\":\"" + bill_type + "\",\"code\":\"" + code + "\",\"status\":\"" + status + "\"}"

}

case class DiscountsResult(id: String, accounts_id: String, bill_type: String, code: String, status: String, created_at: String) {

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.billing.DiscountsResultSerialization
    val preser = new DiscountsResultSerialization()
    toJSON(this)(preser.writer) //where does this JSON from?
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object DiscountsResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[DiscountsResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.billing.DiscountsResultSerialization
    val preser = new DiscountsResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[DiscountsResult] = (Validation.fromTryCatchThrowable[net.liftweb.json.JValue,Throwable] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

object Discounts {
  implicit val formats = DefaultFormats
  private val riak = GWRiak("discounts")

  //implicit def EventsResultsSemigroup: Semigroup[EventsResults] = Semigroup.instance((f1, f2) => f1.append(f2))


  val metadataKey = "Discounts"
  val metadataVal = "Discounts Creation"
  val bindex = "Discounts"

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, email.
   * parses the json, and converts it to eventsinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the account id information is looked up.
   * If the account id is looked up successfully, then yield the GunnySack object.
   */
  private def mkGunnySack(email: String, input: String): ValidationNel[Throwable, Option[GunnySack]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.billing.Discounts", "mkGunnySack:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", input))

    val DiscountsInput: ValidationNel[Throwable, DiscountsInput] = (Validation.fromTryCatchThrowable[DiscountsInput, Throwable] {
      parse(input).extract[DiscountsInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      discount <- DiscountsInput
      aor <- (models.Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t })
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "dst").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      val bvalue = Set(aor.get.id)
      val json = new DiscountsResult(uir.get._1 + uir.get._2, aor.get.id, discount.bill_type, discount.code, discount.status, Time.now.toString).toJson(false)
      new GunnySack(uir.get._1 + uir.get._2, json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

  /*
   * create new discount for the user.
   */

  def create(email: String, input: String): ValidationNel[Throwable, Option[DiscountsResult]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.Discounts", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", input))

    (mkGunnySack(email, input) leftMap { err: NonEmptyList[Throwable] =>
      new ServiceUnavailableError(input, (err.list.map(m => m.getMessage)).mkString("\n"))
    }).toValidationNel.flatMap { gs: Option[GunnySack] =>
      (riak.store(gs.get) leftMap { t: NonEmptyList[Throwable] => t }).
        flatMap { maybeGS: Option[GunnySack] =>
          maybeGS match {
            case Some(thatGS) => (parse(thatGS.value).extract[DiscountsResult].some).successNel[Throwable]
            case None => {
              play.api.Logger.warn(("%-20s -->[%s]").format("Discounts created. success", "Scaliak returned => None. Thats OK."))
              (parse(gs.get.value).extract[DiscountsResult].some).successNel[Throwable];
            }
          }
        }
    }
  }

  
  def findById(discID: Option[List[String]]): ValidationNel[Throwable, DiscountsResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.Discounts", "findByAccId:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("discountsID", discID))
    (discID map {
      _.map { dis_id =>
        play.api.Logger.debug(("%-20s -->[%s]").format("disc ID", dis_id))
        (riak.fetch(dis_id) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(dis_id, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[GunnySack] =>
          xso match {
            case Some(xs) => {
              //JsonScalaz.Error doesn't descend from java.lang.Error or Throwable. Screwy.
               (Validation.fromTryCatchThrowable[DiscountsResult,Throwable] {
                parse(xs.value).extract[DiscountsResult]
              } leftMap { t: Throwable => new MalformedBodyError(xs.value, t.getMessage) }).toValidationNel.flatMap { j: DiscountsResult =>
                play.api.Logger.debug(("%-20s -->[%s]").format("Discounts result", j))
                Validation.success[Throwable, DiscountsResults](nels(j.some)).toValidationNel //screwy kishore, every element in a list ?
              }
            }
            case None => {
              Validation.failure[Throwable, DiscountsResults](new ResourceItemNotFound(dis_id, "")).toValidationNel
            }
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((DiscountsResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head.
  }

  
  
   def findByEmail(email: String): ValidationNel[Throwable, DiscountsResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("billing.Discounts", "findByEmail:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    val res = eitherT[IO, NonEmptyList[Throwable], ValidationNel[Throwable, DiscountsResults]] {
      (((for {
        aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t }) //captures failure on the left side, success on right ie the component before the (<-)
      } yield {
        val bindex = ""
        val bvalue = Set("")
        play.api.Logger.debug(("%-20s -->[%s]").format("billing.Discounts", "findByEmail" + aor.get.id))
        new GunnySack("discounts", aor.get.id, RiakConstants.CTYPE_TEXT_UTF8,
          None, Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
      }) leftMap { t: NonEmptyList[Throwable] => t } flatMap {
        gs: Option[GunnySack] => riak.fetchIndexByValue(gs.get)
      } map { nm: List[String] =>
        (if (!nm.isEmpty) findById(nm.some) else
          new ResourceItemNotFound(email, "Discounts = nothing found for the user.").failureNel[DiscountsResults])
      }).disjunction).pure[IO]
    }.run.map(_.validation).unsafePerformIO
    res.getOrElse(new ResourceItemNotFound(email, "Discounts = nothing found for the users.").failureNel[DiscountsResults])
  }
  
}
