package com.me.rocks.kafka;

import com.me.rocks.kafka.avro.AvroModel;
import com.me.rocks.kafka.avro.GenericRecordMapper;
import com.me.rocks.kafka.config.RocksThreadFactory;
import com.me.rocks.kafka.delivery.health.KafkaHealthChecker;
import com.me.rocks.kafka.delivery.strategies.DeliveryStrategy;
import com.me.rocks.kafka.delivery.DeliveryStrategyEnum;
import com.me.rocks.kafka.exception.RocksProducerException;
import com.me.rocks.kafka.queue.RocksQueueFactory;
import com.me.rocks.kafka.queue.message.KVRecord;
import com.me.rocks.kafka.queue.serialize.KryoSerializer;
import com.me.rocks.kafka.queue.serialize.Serializer;
import com.me.rocks.queue.QueueItem;
import com.me.rocks.queue.RocksQueue;
import com.me.rocks.queue.exception.RocksQueueException;
import org.apache.avro.generic.GenericData.Record;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RocksProducer {
    private static final Logger log = LoggerFactory.getLogger(RocksProducer.class);

    private final String topic;
    private final Serializer serializer;
    private final RocksQueue queue;
    private final List<Listener> listeners = new LinkedList<Listener>();
    private final ExecutorService executorService;

    public RocksProducer(final String topic, final Serializer serializer, final Listener listener, final DeliveryStrategy strategy) {
        this.topic = topic;
        this.serializer = serializer;
        this.listeners.add(listener);
        this.queue = RocksQueueFactory.INSTANCE.createQueue(topic);

        registerShutdownHook(strategy);

        executorService = Executors.newSingleThreadExecutor(new RocksThreadFactory("rocks_queue_consumer"));
        executorService.submit(() -> {
            while(true) {
                if(queue.isEmpty()) {
                    continue;
                }

                if(!KafkaHealthChecker.INSTANCE.isKafkaBrokersAlive()){
                    continue;
                }

                QueueItem consume = queue.consume();

                if(consume == null)
                    continue;

                listener.beforeSend(consume.getIndex());
                strategy.delivery(getProducerRecord(topic, serializer, consume.getValue()), queue, listeners);
                listener.afterSend(consume.getIndex());
            }
        });

    }

    private ProducerRecord<String, Record> getProducerRecord(String topic, Serializer serializer, byte[] value) {
        KVRecord kvRecord = serializer.deserialize(value);

        return new ProducerRecord<>(topic, kvRecord.getKey(),
                GenericRecordMapper.mapObjectToRecord(kvRecord.getModel()));
    }

    public void send(String key, AvroModel value) throws RocksProducerException {
        KVRecord kvRecord = new KVRecord(key, value);
        try {
            queue.enqueue(serializer.serialize(kvRecord));
        } catch (RocksQueueException e) {
            throw new RocksProducerException(e);
        }
    }

    public static RocksProducer create(final String topic) {
        return createReliable(topic);
    }

    public static RocksProducer createReliable(final String topic) {
        return RocksProducer.builder()
                .topic(topic)
                .serializer(new KryoSerializer())
                .kafkaDeliveryStrategy(DeliveryStrategyEnum.RELIABLE)
                .build();
    }

    public static RocksProducer createFast(final String topic) {
        return RocksProducer.builder()
                .topic(topic)
                .serializer(new KryoSerializer())
                .kafkaDeliveryStrategy(DeliveryStrategyEnum.FAST)
                .build();
    }

    public static class Builder {
        private String topic;
        private Serializer serializer;
        private Listener listener;
        private DeliveryStrategy strategy;

        public Builder() {

        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder serializer(Serializer serializer) {
            this.serializer = serializer;
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        public Builder kafkaDeliveryStrategy(DeliveryStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public RocksProducer build() {
            Assert.notNull(topic, "Topic must not be null");

            if(serializer == null) {
                serializer = new KryoSerializer();
            }

            if(listener == null) {
                listener = new DefaultListener();
            }

            if(strategy == null) {
                strategy = DeliveryStrategyEnum.RELIABLE;
            }

            return new RocksProducer(topic, serializer, listener, strategy);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private void registerShutdownHook(DeliveryStrategy strategy) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.debug("Closing application...");

            //Close store will close all queue
            RocksQueueFactory.INSTANCE.close();

            // Free resources allocated by Kafka producer
            strategy.clear();

            // Stop kafka health checker
            KafkaHealthChecker.INSTANCE.clear();

            // Shutdown kafka delivery thread
            this.clear();

            log.info("Application closed.");
        }));
    }

    private void clear() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }

    public interface Listener {
        void beforeSend(long index);
        void afterSend(long index);
        void onSendSuccess(String topic, long offset);
        void onSendFail(String topic, String message, Exception exception);
    }

    private static class DefaultListener implements Listener {
        @Override
        public void beforeSend(long index) {
            log.debug("Starting send the #{} of message to kafka", index);
        }

        @Override
        public void afterSend(long index) {
            log.debug("Sending the #{} of message to kafka finished", index);
        }

        @Override
        public void onSendFail(String topic, String message, Exception exception) {
            log.error("Sending data {} to kafka topic {} failed", message, topic, exception);
        }

        @Override
        public void onSendSuccess(String topic, long offset) {
            log.debug("sending data to kafka topic {} success, offset is {}", topic, offset);
        }
    }
}
