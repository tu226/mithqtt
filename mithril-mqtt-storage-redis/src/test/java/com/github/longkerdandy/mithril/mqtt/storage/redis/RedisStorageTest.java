package com.github.longkerdandy.mithril.mqtt.storage.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.longkerdandy.mithril.mqtt.storage.redis.util.JSONs;
import com.github.longkerdandy.mithril.mqtt.util.Topics;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.ValueScanCursor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.*;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.github.longkerdandy.mithril.mqtt.storage.redis.RedisStorage.mapToMqtt;
import static com.github.longkerdandy.mithril.mqtt.storage.redis.RedisStorage.mqttToMap;
import static com.github.longkerdandy.mithril.mqtt.util.Topics.END;

/**
 * Redis Storage Test
 */
public class RedisStorageTest {

    private static RedisStorage redis;

    @BeforeClass
    public static void init() {
        redis = new RedisStorage("localhost", 6379);
        redis.init();
    }

    @AfterClass
    public static void destroy() {
        redis.destroy();
    }

    @Test
    public void connectedTest() throws ExecutionException, InterruptedException {
        complete(redis.addConnectedNodes("client1", "node1"));
        complete(redis.addConnectedNodes("client2", "node1"));
        complete(redis.addConnectedNodes("client3", "node1"));
        complete(redis.addConnectedNodes("client4", "node2"));
        complete(redis.addConnectedNodes("client5", "node2"));

        assert redis.getConnectedNodes("client1").get().contains("node1");
        assert redis.getConnectedNodes("client2").get().contains("node1");
        assert redis.getConnectedNodes("client3").get().contains("node1");
        assert redis.getConnectedNodes("client4").get().contains("node2");
        assert redis.getConnectedNodes("client5").get().contains("node2");

        ValueScanCursor<String> vcs1 = redis.getConnectedClients("node1", "0", 100).get();
        assert vcs1.getValues().contains("client1");
        assert vcs1.getValues().contains("client2");
        assert vcs1.getValues().contains("client3");
        ValueScanCursor<String> vcs2 = redis.getConnectedClients("node2", "0", 100).get();
        assert vcs2.getValues().contains("client4");
        assert vcs2.getValues().contains("client5");

        complete(redis.removeConnectedNodes("client3", "node1"));
        complete(redis.removeConnectedNodes("client4", "node1"));   // not exist

        assert redis.getConnectedNodes("client3").get().isEmpty();
        assert redis.getConnectedNodes("client4").get().contains("node2");

        vcs1 = redis.getConnectedClients("node1", "0", 100).get();
        assert !vcs1.getValues().contains("client3");
        vcs2 = redis.getConnectedClients("node2", "0", 100).get();
        assert vcs2.getValues().contains("client4");
    }

    @Test
    public void existTest() throws ExecutionException, InterruptedException {
        assert redis.isClientExist("client1").get() == 0;

        redis.markClientExist("client1").get();

        assert redis.isClientExist("client1").get() == 1;
    }

    @Test
    public void inFlightTest() throws IOException, InterruptedException, ExecutionException {
        String json = "{\"menu\": {\n" +
                "  \"id\": \"file\",\n" +
                "  \"value\": \"File\",\n" +
                "  \"popup\": {\n" +
                "    \"menuItem\": [\n" +
                "      {\"value\": \"New\", \"onclick\": \"CreateNewDoc()\"},\n" +
                "      {\"value\": \"Open\", \"onclick\": \"OpenDoc()\"},\n" +
                "      {\"value\": \"Close\", \"onclick\": \"CloseDoc()\"}\n" +
                "    ]\n" +
                "  }\n" +
                "}}";
        JsonNode jn = JSONs.ObjectMapper.readTree(json);
        MqttMessage mqtt = MqttMessageFactory.newMessage(
                new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                new MqttPublishVariableHeader("menuTopic", 123456),
                Unpooled.wrappedBuffer(JSONs.ObjectMapper.writeValueAsBytes(jn))
        );

        complete(redis.addInFlightMessage("client1", true, 123456, mqttToMap(mqtt)));
        mqtt = mapToMqtt(redis.getInFlightMessage("client1", 123456).get());

        assert mqtt.fixedHeader().messageType() == MqttMessageType.PUBLISH;
        assert !mqtt.fixedHeader().isDup();
        assert mqtt.fixedHeader().qosLevel() == MqttQoS.AT_LEAST_ONCE;
        assert !mqtt.fixedHeader().isRetain();
        assert mqtt.fixedHeader().remainingLength() == 0;
        assert ((MqttPublishVariableHeader) mqtt.variableHeader()).topicName().equals("menuTopic");
        assert ((MqttPublishVariableHeader) mqtt.variableHeader()).messageId() == 123456;
        jn = JSONs.ObjectMapper.readTree(((ByteBuf) mqtt.payload()).array());
        assert jn.get("menu").get("id").textValue().endsWith("file");
        assert jn.get("menu").get("value").textValue().endsWith("File");
        assert jn.get("menu").get("popup").get("menuItem").get(0).get("value").textValue().equals("New");
        assert jn.get("menu").get("popup").get("menuItem").get(0).get("onclick").textValue().equals("CreateNewDoc()");
        assert jn.get("menu").get("popup").get("menuItem").get(1).get("value").textValue().equals("Open");
        assert jn.get("menu").get("popup").get("menuItem").get(1).get("onclick").textValue().equals("OpenDoc()");
        assert jn.get("menu").get("popup").get("menuItem").get(2).get("value").textValue().equals("Close");
        assert jn.get("menu").get("popup").get("menuItem").get(2).get("onclick").textValue().equals("CloseDoc()");

        assert redis.getAllInFlightMessageIds("client1", true).get().contains("123456");

        complete(redis.removeInFlightMessage("client1", true, 123456));

        assert redis.getInFlightMessage("client1", 123456).get().isEmpty();
        assert !redis.getAllInFlightMessageIds("client1", true).get().contains("123456");
    }

    @Test
    public void subscriptionTest() throws ExecutionException, InterruptedException {
        complete(redis.updateSubscription("client1", true, Topics.sanitizeTopicFilter("a/+/e"), "0"));
        complete(redis.updateSubscription("client1", true, Topics.sanitizeTopicFilter("a/+"), "1"));
        complete(redis.updateSubscription("client1", true, Topics.sanitizeTopicName("a/c/e"), "2"));
        complete(redis.updateSubscription("client2", true, Topics.sanitizeTopicFilter("a/#"), "0"));
        complete(redis.updateSubscription("client2", true, Topics.sanitizeTopicFilter("a/+"), "1"));
        complete(redis.updateSubscription("client2", true, Topics.sanitizeTopicName("a/c/e"), "2"));

        assert redis.getClientSubscriptions("client1", true).get().get("a/+/e/^").equals("0");
        assert redis.getClientSubscriptions("client1", true).get().get("a/+/^").equals("1");
        assert redis.getClientSubscriptions("client1", true).get().get("a/c/e/^").equals("2");
        assert redis.getClientSubscriptions("client2", true).get().get("a/#/^").equals("0");
        assert redis.getClientSubscriptions("client2", true).get().get("a/+/^").equals("1");
        assert redis.getClientSubscriptions("client2", true).get().get("a/c/e/^").equals("2");

        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+/e")).get().get("client1").equals("0");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().get("client1").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().get("client2").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicName("a/c/e")).get().get("client1").equals("2");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicName("a/c/e")).get().get("client2").equals("2");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/#")).get().get("client2").equals("0");

        complete(redis.removeSubscription("client1", true, Topics.sanitizeTopicFilter("a/+")));

        assert !redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().containsKey("client1");
        assert !redis.getClientSubscriptions("client1", true).get().containsKey("a/+/^");

        complete(redis.removeAllSubscriptions("client2", true));

        assert redis.getClientSubscriptions("client2", true).get().isEmpty();
        assert !redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().containsKey("client2");
        assert !redis.getTopicSubscriptions(Topics.sanitizeTopicName("a/c/e")).get().containsKey("client2");
        assert !redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/#")).get().containsKey("client2");
    }

    @Test
    public void matchTopicFilterTest() throws ExecutionException, InterruptedException {
        complete(redis.updateSubscription("client1", true, Topics.sanitizeTopicFilter("a/+/e"), "0"));
        complete(redis.updateSubscription("client1", true, Topics.sanitizeTopicFilter("a/+"), "1"));
        complete(redis.updateSubscription("client1", true, Topics.sanitizeTopicFilter("a/c/f/#"), "2"));
        complete(redis.updateSubscription("client2", true, Topics.sanitizeTopicFilter("a/#"), "0"));
        complete(redis.updateSubscription("client2", true, Topics.sanitizeTopicFilter("a/c/+/+"), "1"));
        complete(redis.updateSubscription("client2", true, Topics.sanitizeTopicFilter("a/d/#"), "2"));

        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+/e")).get().get("client1").equals("0");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().get("client1").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/c/f/#")).get().get("client1").equals("2");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/#")).get().get("client2").equals("0");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/c/+/+")).get().get("client2").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/d/#")).get().get("client2").equals("2");

        Map<String, String> result = new HashMap<>();
        match(Topics.sanitizeTopicName("a/c/f"), 0, result);
        assert result.get("client1").equals("2");
        assert result.get("client2").equals("0");

        result.clear();
        match(Topics.sanitizeTopicName("a/d/e"), 0, result);
        assert result.get("client1").equals("0");
        assert result.containsKey("client2");

        result.clear();
        match(Topics.sanitizeTopicName("a/b/c/d"), 0, result);
        assert !result.containsKey("client1");
        assert result.get("client2").equals("0");
    }

    @After
    public void clear() throws ExecutionException, InterruptedException {
        redis.conn.async().flushdb().get();
    }

    /**
     * Wait asynchronous tasks completed
     */
    protected void complete(List<RedisFuture> futures) throws InterruptedException {
        for (RedisFuture future : futures) {
            future.await(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Traverse topic tree, find all matched subscribers
     */
    protected void match(List<String> topicLevels, int index, Map<String, String> result) throws ExecutionException, InterruptedException {
        List<String> children = redis.matchTopicFilterLevel(topicLevels, index).get();
        // last one
        if (children.size() == 2) {
            int c = children.get(0) == null ? 0 : Integer.parseInt(children.get(0)); // char
            int s = children.get(1) == null ? 0 : Integer.parseInt(children.get(1)); // #
            if (c > 0) {
                result.putAll(redis.getTopicSubscriptions(topicLevels).get());
            }
            if (s > 0) {
                List<String> newTopicLevels = new ArrayList<>(topicLevels.subList(0, index));
                newTopicLevels.add("#");
                newTopicLevels.add(END);
                result.putAll(redis.getTopicSubscriptions(newTopicLevels).get());
            }
        }
        // not last one
        else if (children.size() == 3) {
            int c = children.get(0) == null ? 0 : Integer.parseInt(children.get(0)); // char
            int s = children.get(1) == null ? 0 : Integer.parseInt(children.get(1)); // #
            int p = children.get(2) == null ? 0 : Integer.parseInt(children.get(2)); // +
            if (c > 0) {
                match(topicLevels, index + 1, result);
            }
            if (s > 0) {
                List<String> newTopicLevels = new ArrayList<>(topicLevels.subList(0, index));
                newTopicLevels.add("#");
                newTopicLevels.add(END);
                result.putAll(redis.getTopicSubscriptions(newTopicLevels).get());
            }
            if (p > 0) {
                List<String> newTopicLevels = new ArrayList<>(topicLevels);
                newTopicLevels.set(index, "+");
                match(newTopicLevels, index + 1, result);
            }
        }
    }
}
