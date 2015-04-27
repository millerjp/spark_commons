package pro.foundev.scala

/*
 * Copyright 2014 Foundational Development
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

object BulkDelete extends CassandraCapable {

  def main(args: Array[String]): Unit = {

    val context = connectToCassandra()
    val rdd = context.rdd
    val connector = context.connector

    rdd.where("version=1").map(x => {
      connector.withSessionDo(session => {
        val deleteStatement = session.prepare(s"delete from ${getFullTableName()} where id = ? and version = 1")
        val id = x.get[Integer](0)
        session.executeAsync(deleteStatement.bind(id))
      })
    }).foreach(x => x.getUninterruptibly())
  }
}
