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
package pro.foundev.messaging;

import com.rabbitmq.client.*;
import org.apache.spark.storage.StorageLevel;

import java.io.IOException;


public class RabbitMQReceiver extends AbstractQueueReceiver {

    private String host;
    private  String queueName;
    private final StorageLevel storageLevel;
    private Connection connection = null;
    private Channel channel = null;

    public RabbitMQReceiver() {
        super(StorageLevel.MEMORY_ONLY());
        this.storageLevel = StorageLevel.MEMORY_ONLY();
        //probably wrong
    }

    @Override
    public StorageLevel storageLevel() {
        return this.storageLevel;
    }

    @Override
    public void onStart() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory = new ConnectionFactory();
        try {
            connection = factory.newConnection();
            channel = connection.createChannel();

            Consumer consumer = new DefaultConsumer(channel){
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    store(new String(body, "UTF-8"));

                }
            };
            channel.basicConsume(queueName, true, consumer);

        } catch (IOException e) {
            restart("error connecting to message queue", e);
        }
    }


    @Override
    public void onStop() {

        if(channel!=null){
            try {
                channel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if(connection!=null){
            try {
                connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setHost(String host) {
        this.host = host;
    }

    @Override
    public void setQueueName(String qname) {
        this.queueName = qname;
    }
}
