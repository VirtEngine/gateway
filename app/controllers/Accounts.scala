/* 
** Copyright [2012] [Megam Systems]
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
package controllers

import play.api._
import play.api.mvc._
import models._
import controllers.stack.HMACElement
import controllers.stack._
import java.util.concurrent.atomic.AtomicInteger
/**
 * @author rajthilak
 *
 */

/*
 * This controller performs HMAC authentication and access riak
 * If HMAC authentication is true then post or list the accounts are executed
 *  
 */
object Accounts extends Controller with HMACElement with SourceElement {
  
  //This ain't the rightway, but we'll move to use snowflake.
  val counter = new AtomicInteger()
  
  /*
   * parse.tolerantText to parse the RawBody 
   * get requested body and put into the riak bucket
   */
  def post = StackAction(parse.tolerantText) { implicit request =>
    val input = (request.body).toString()
  
    val id = "content" + counter.incrementAndGet()     
    models.Accounts.put("accounts", id, input)
    
    Ok("Account created successfully for id" + "with account_id:"+id)
  }

}