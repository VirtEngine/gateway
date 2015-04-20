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
package test.billing

import org.specs2.mutable._
import org.specs2.Specification
import java.net.URL
import org.specs2.matcher.MatchResult
import org.specs2.execute.{ Result => SpecsResult }
import com.stackmob.newman.response.{ HttpResponse, HttpResponseCode }
import com.stackmob.newman._
import com.stackmob.newman.dsl._
import models.billing._
import models.billing.Credithistories
import test.{ Context }
/**
 * @author rajthilak
 *
 */
class CredithistoriesSpec extends Specification {

  def is =
    "CredithistoriesSpec".title ^ end ^ """
  CredithistoriesSpec is the implementation that calls the megam_play API server with the /credithistories url
  """ ^ end ^
      "The Client Should" ^
      "Correctly do POST  requests with an valid datas" ! create.succeeds ^
      end

    case object create extends Context {

    protected override def urlSuffix: String = "credithistories/content"

    protected override def bodyToStick: Option[String] = {
      val contentToEncode = "{" +
        "\"account_id\": \"565656\"," +
        "\"bill_type\":\"456436\"," +    
        "\"credit_amount\": \"2000\"," +
        "\"currency_type\":\"USD\"," +  
        "}"

      Some(new String(contentToEncode))
    }
    protected override def headersOpt: Option[Map[String, String]] = None

    private val post = POST(url)(httpClient)
      .addHeaders(headers)
      .addBody(body)

    def succeeds: SpecsResult = {
      val resp = execute(post)
      resp.code must beTheSameResponseCodeAs(HttpResponseCode.Created)
    }
  }

}