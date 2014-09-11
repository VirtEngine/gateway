/* 
** Copyright [2013-2014] [Megam Systems]
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

package models.tosca

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
//import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps
import org.megam.util.Time
import scala.collection.mutable.ListBuffer
import controllers.stack._
import controllers.Constants._
import controllers.funnel.FunnelErrors._
import models.tosca._
import models._
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

case class AssemblyResult(id: String, name: String, components: models.tosca.ComponentLinks, policies: String, inputs: String, operations: String, created_at: String) {
  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.AssemblyResultSerialization
    val preser = new AssemblyResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object AssemblyResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[AssemblyResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.AssemblyResultSerialization
    val preser = new AssemblyResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[AssemblyResult] = (Validation.fromTryCatch {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

case class Assembly(name: String, components: models.tosca.ComponentsList, policies: String, inputs: String, operations: String) {
  val json = "{\"name\":\"" + name + "\",\"components\":" + ComponentsList.toJson(components, true) + ",\"policies\":\"" + policies +
    "\",\"inputs\":\"" + inputs + "\",\"operations\":\"" + operations + "\"}"

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new models.json.tosca.AssemblySerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object Assembly {

  def empty: Assembly = new Assembly(new String(), ComponentsList.empty, new String(), new String, new String())

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[Assembly] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new models.json.tosca.AssemblySerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[Assembly] = (Validation.fromTryCatch {
    play.api.Logger.debug(("%-20s -->[%s]").format("---json------------------->", json))
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }
}

object AssembliesList {
  implicit val formats = DefaultFormats

  implicit def AssembliesResultsSemigroup: Semigroup[AssembliesResults] = Semigroup.instance((f1, f2) => f1.append(f2))

  val emptyRR = List(Assembly.empty)
  def toJValue(nres: AssembliesList): JValue = {

    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.AssembliesListSerialization.{ writer => AssembliesListWriter }
    toJSON(nres)(AssembliesListWriter)
  }

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[AssembliesList] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.AssembliesListSerialization.{ reader => AssembliesListReader }
    fromJSON(jValue)(AssembliesListReader)
  }

  def toJson(nres: AssembliesList, prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue(nres)))
  } else {
    compactRender(toJValue(nres))
  }

  def apply(assemblyList: List[Assembly]): AssembliesList = { println(assemblyList); assemblyList }

  def empty: List[Assembly] = emptyRR

  private val riak = GWRiak("assembly")

  val metadataKey = "ASSEMBLY"
  val metadataVal = "Assembly Creation"
  val bindex = "assembly"

  def createLinks(email: String, input: AssembliesList): ValidationNel[Throwable, AssembliesResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.AssembliesList", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("yaml", input))

    val res = (input map {
      asminp =>
        play.api.Logger.debug(("%-20s -->[%s]").format("assembly", asminp))
        (create(email, asminp))
    }).foldRight((AssembliesResults.empty).successNel[Throwable])(_ +++ _)

    play.api.Logger.debug(("%-20s -->[%s]").format("models.tosca.Assembly", res))
    res.getOrElse(new ResourceItemNotFound(email, "nodes = ah. ouh. ror some reason.").failureNel[AssembliesResults])
    res
  }

  /*
   * create new market place item with the 'name' of the item provide as input.
   * A index name assemblies name will point to the "csars" bucket
   */
  def create(email: String, input: Assembly): ValidationNel[Throwable, AssembliesResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.AssembliesList", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("yaml", input))

    for {
      ogsi <- mkGunnySack(email, input) leftMap { err: NonEmptyList[Throwable] => err }
      nrip <- AssemblyResult.fromJson(ogsi.get.value) leftMap { t: NonEmptyList[net.liftweb.json.scalaz.JsonScalaz.Error] => println("osgi\n" + ogsi.get.value); play.api.Logger.debug(JSONParsingError(t).toString); nels(JSONParsingError(t)) }
      ogsr <- riak.store(ogsi.get) leftMap { t: NonEmptyList[Throwable] => play.api.Logger.debug("--------> ooo"); t }
    } yield {
      play.api.Logger.debug(("%-20s -->[%s],riak returned: %s").format("Assembly.created successfully", email, ogsr))
      ogsr match {
        case Some(thatGS) => {
          nels(AssemblyResult(thatGS.key, nrip.name, nrip.components, nrip.policies, nrip.inputs, nrip.operations, Time.now.toString()).some)
        }
        case None => {
          play.api.Logger.warn(("%-20s -->[%s]").format("Node.created successfully", "Scaliak returned => None. Thats OK."))
          nels(AssemblyResult(ogsi.get.key, nrip.name, nrip.components, nrip.policies, nrip.inputs, nrip.operations, Time.now.toString()).some)
        }
      }
    }

  }

  private def mkGunnySack(email: String, rip: Assembly): ValidationNel[Throwable, Option[GunnySack]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.Assembly", "mkGunnySack:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", rip))

    for {
      aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t })
      com <- (ComponentsList.createLinks(email, rip.components) leftMap { t: NonEmptyList[Throwable] => t })
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "asm").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      val bvalue = Set(aor.get.id)
      var components_links = new ListBuffer[String]()
      for (component <- com) {
        component match {
          case Some(value) => components_links += value.id
          case None        => components_links += ""
        }
      }
      val json = AssemblyResult(uir.get._1 + uir.get._2, rip.name, components_links.toList, rip.policies, rip.inputs, rip.operations, Time.now.toString).toJson(false)
      new GunnySack((uir.get._1 + uir.get._2), json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

}
 