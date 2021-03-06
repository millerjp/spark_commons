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
package pro.foundev.examples.spark_streaming.java.interactive.smartconsumer;

import com.google.common.base.Joiner;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import pro.foundev.examples.spark_streaming.java.messaging.AbstractRabbitMQConnector;
import pro.foundev.examples.spark_streaming.java.cassandra.CassandraConnectionContext;
import pro.foundev.examples.spark_streaming.java.dto.Transaction;
import pro.foundev.examples.spark_streaming.java.dto.Warning;
import scala.Tuple2;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ResponseWriter  extends AbstractRabbitMQConnector {
    public ResponseWriter(String[] args) {
        super(args, "cfs:///smart_consume_spark_job");
    }

    public static void main(String[] args) {
        new ResponseWriter(args).startJob();
    }

    private static final String EXCHANGE_NAME = "warnings_response";
    private static Thread mainThread;

    @Override
    protected JavaStreamingContext createContext() {
        mainThread = Thread.currentThread();
        final Messenger messenger = initRabbitMqResponseQueue();

        CassandraConnectionContext connectionContext = connectToCassandra();
        JavaDStream<String> rdd = connectionContext.getRDD();
        JavaStreamingContext sparkContext = connectionContext.getStreamingContext();
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        JavaDStream<Tuple2<String,Transaction>> transactionList
                = rdd.map(new
                                  Function<String, Tuple2<String, Transaction>>() {
                                      @Override
                                      public Tuple2<String, Transaction> call(String line) throws Exception {
                                          String[] columns = line.split(",");
                                          String taxId = columns[0];
                                          String name = columns[1];
                                          String merchant = columns[2];
                                          BigDecimal amount = new BigDecimal(columns[3]);
                                          Date transactionDate;
                                          try {
                                              transactionDate = format.parse(columns[4]);
                                          } catch (ParseException e) {
                                              e.printStackTrace();
                                              throw new RuntimeException(e);
                                          }
                                          String tranId = columns[5];

                                          System.out.println(line);
                                          Tuple2<String, Transaction> transaction = new Tuple2<>(taxId,
                                                  new Transaction(name, merchant, amount, transactionDate, tranId));
                                          return transaction;
                                      }
                                  }
        );
        //setup warning on more than certain number of transactions by user in a 60 second window, every 10 seconds
        JavaPairDStream<String, BigDecimal> warnings = transactionList.window(new Duration(60000), new Duration(10000))
                .mapToPair(new PairFunction<Tuple2<String, Transaction>, String, BigDecimal>() {
                    @Override
                    public Tuple2<String, BigDecimal> call(Tuple2<String, Transaction> transaction) throws Exception {
                        String taxId = transaction._1();
                        BigDecimal amount = transaction._2().getAmount();
                        return new Tuple2<>(taxId, amount);
                    }
                })
                .reduceByKey(new Function2<BigDecimal, BigDecimal, BigDecimal>() {
                    @Override
                    public BigDecimal call(BigDecimal transactionAmount1, BigDecimal transactionAmount2) throws Exception {
                        return transactionAmount1.add(transactionAmount2);
                    }
                }).filter(new Function<Tuple2<String, BigDecimal>, Boolean>() {
                              @Override
                              public Boolean call(Tuple2<String, BigDecimal> transactionSumByTaxId) throws Exception {
                                  //returns true if total is greater than 999
                                  Boolean result = transactionSumByTaxId._2().compareTo(new BigDecimal(999)) == 1;
                                  System.out.println("tran " + transactionSumByTaxId._1() + " with amount " + transactionSumByTaxId._2());
                                  if (result) {
                                      System.out.println("warning " + transactionSumByTaxId._1() + " has value " + transactionSumByTaxId._2() + " is greater than 999");
                                  }
                                  return result;
                              }
                          }
                );
        JavaDStream<String> mappedWarnings = warnings.map(new Function<Tuple2<String, BigDecimal>, Warning>() {
            @Override
            public Warning call(Tuple2<String, BigDecimal> warnings) throws Exception {
                Warning warning = new Warning();
                String id = warnings._1();
                String[] idCols = id.split(":");
                warning.setSsn(idCols[0]);
                warning.setId(UUID.fromString(idCols[1]));
                warning.setAmount(warnings._2());
                warning.setRule("OVER_DOLLAR_AMOUNT");
                return warning;
            }
        }).map(new Function<Warning, String>() {

            @Override
            public String call(Warning warning) throws Exception {
                List<String> cols = new ArrayList<>();
                cols.add(String.valueOf(warning.getId()));
                cols.add(String.valueOf(warning.getAmount()));
                cols.add(warning.getRule());
                cols.add(warning.getSsn());
                return Joiner.on(",").join(cols);
            }
        });
        mappedWarnings.foreachRDD(new Function2<JavaRDD<String>, Time, Void>() {
            @Override
            public Void call(JavaRDD<String> v1, Time v2) throws Exception {
                v1.foreachPartition(new VoidFunction<Iterator<String>>() {
                    @Override
                    public void call(Iterator<String> stringIterator) throws Exception {
                        while(stringIterator.hasNext()){
                            messenger.sendMessage(stringIterator.next());
                        }
                    }
                });
                return null;
            }
        });
        return sparkContext;
    }

    private Messenger initRabbitMqResponseQueue() {

        ConnectionFactory factory = new ConnectionFactory();
        final Connection connection;
        final Channel channel;
        factory.setHost("localhost");
        try {
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.exchangeDeclare(EXCHANGE_NAME, "fanout");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                ResponseWriter.mainThread.interrupt();
                try {
                    channel.close();
                    connection.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        return new MessengerImpl(channel);
    }
    public interface Messenger {
        void sendMessage(String message);
    }

    public class MessengerImpl implements Messenger, Serializable{
        private final Channel channel;

        public MessengerImpl(Channel channel){

            this.channel = channel;
        }
        public void sendMessage(String message) {
            try {
                channel.basicPublish(EXCHANGE_NAME, "", null, message.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
