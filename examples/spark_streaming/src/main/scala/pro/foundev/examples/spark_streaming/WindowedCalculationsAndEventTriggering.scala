/*
 *  Copyright 2015 Foundational Development
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package pro.foundev.examples.spark_streaming

import _root_.java.text.SimpleDateFormat

//import java.text.SimpleDateFormat

import com.datastax.driver.core.utils.UUIDs
import org.apache.spark.streaming.{StreamingContext, Seconds}
import org.apache.spark.streaming.StreamingContext._
import pro.foundev.examples.spark_streaming.messaging.RabbitMqCapable
import pro.foundev.examples.spark_streaming.utils.Args

object WindowedCalculationsAndEventTriggering {
  def main(args: Array[String])= {
    val master = Args.parseMaster(args)
    new WindowedCalculationsAndEventTriggering(master).startJob()
  }
}
class WindowedCalculationsAndEventTriggering(master: String )
    extends RabbitMqCapable(master, "windowed_checkpoint") {

  def createContext(): StreamingContext = {
    val (rdd, sparkContext, connector) = connectToExchange()
    val format = new SimpleDateFormat("yyyy-MM-dd")
    val transactionList = rdd.map(line => {
      val columns = line.split(",")
      val taxId = columns(0)
      val name = columns(1)
      val merchant = columns(2)
      val amount = BigDecimal(columns(3))
      val transactionDate = format.parse(columns(4))
      println(line)
      (taxId, (name, merchant, amount, transactionDate))
    }).cache()

    val warningsTableName = "warnings"
    connector.withSessionDo(session => session.execute(s"DROP TABLE IF EXISTS ${keySpaceName}.${warningsTableName}"))
    connector.withSessionDo(session => session.execute("CREATE TABLE IF NOT EXISTS " +
      s"${keySpaceName}.${warningsTableName} (ssn text, id timeuuid, amount decimal, rule text, PRIMARY KEY(ssn, id))"))
    //setup warning on more than certain number of transactions by user in a 60 second window, every 10 seconds


    transactionList.window(Seconds(60), Seconds(10))
      .map(record => (record._1, record._2._3))
      .reduceByKey(_ + _)
      .filter(_._2 > BigDecimal(999))
      .foreachRDD(rdd =>
      rdd.foreachPartition(rowIterator => {
        connector.withSessionDo(session => {
          //preparing per partition, this is not needed but is likely much more direct
          val prepared = session.prepare(s"INSERT INTO ${keySpaceName}.${warningsTableName} (ssn, id, amount, rule)" +
            "values (?,?,?,'OVER_DOLLAR_AMOUNT')")
          while (rowIterator.hasNext) {
            val row = rowIterator.next()
            println(s"Warning about user with taxId ${row._1} they've submitted ${row._2} in the past 60 seconds")
            //FIXME: need to handle time better
            val now = UUIDs.timeBased()
            session.execute(prepared.bind(row._1, now, row._2.bigDecimal))
          }
        })
      }

      ))
    sparkContext
  }
}
