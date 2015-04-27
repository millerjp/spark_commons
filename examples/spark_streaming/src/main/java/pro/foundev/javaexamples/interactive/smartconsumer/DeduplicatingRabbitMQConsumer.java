
package pro.foundev.javaexamples.interactive.smartconsumer;

import com.google.common.base.Stopwatch;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DeduplicatingRabbitMQConsumer {
    public static void main(String[] args){
        new DeduplicatingRabbitMQConsumer().run();
    }
    private static final String EXCHANGE_NAME = "warnings_response";
    public void run(){
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try {
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            QueueingConsumer consumer = new QueueingConsumer(channel);
            channel.basicConsume(EXCHANGE_NAME, true, consumer);
            Set<String> messages = new HashSet<>();
            Stopwatch stopwatch = new Stopwatch();
            stopwatch.start();
            while (true) {
                QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                String message = new String(delivery.getBody());
                messages.add(message);

                if(stopwatch.elapsed(TimeUnit.MILLISECONDS)>1000l){
                    System.out.println("it should be safe to submit messages now");
                    for(String m:messages){
                        //notifying user interface
                        System.out.println(m);
                    }
                    stopwatch.stop();
                    stopwatch.reset();
                    messages.clear();
                }
                if(messages.size()>100000000){
                    System.out.println("save off to file system and clear before we lose everything");
                    messages.clear();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private void saveMessages(Collection<String> messages){
        //save off somewhere
    }


}
