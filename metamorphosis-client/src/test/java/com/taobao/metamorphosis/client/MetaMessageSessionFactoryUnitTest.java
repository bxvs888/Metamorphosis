/*
 * (C) 2007-2012 Alibaba Group Holding Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Authors:
 *   wuhua <wq163@163.com> , boyan <killme2008@gmail.com>
 */
package com.taobao.metamorphosis.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.taobao.gecko.service.Connection;
import com.taobao.gecko.service.RemotingFactory;
import com.taobao.gecko.service.RemotingServer;
import com.taobao.gecko.service.RequestProcessor;
import com.taobao.gecko.service.config.ServerConfig;
import com.taobao.metamorphosis.client.consumer.ConsumerConfig;
import com.taobao.metamorphosis.client.consumer.MessageConsumer;
import com.taobao.metamorphosis.client.producer.MessageProducer;
import com.taobao.metamorphosis.client.producer.RoundRobinPartitionSelector;
import com.taobao.metamorphosis.exception.InvalidConsumerConfigException;
import com.taobao.metamorphosis.network.BooleanCommand;
import com.taobao.metamorphosis.network.MetamorphosisWireFormatType;
import com.taobao.metamorphosis.network.StatsCommand;


public class MetaMessageSessionFactoryUnitTest {
    private MetaMessageSessionFactory messageSessionFactory;


    @Before
    public void setUp() throws Exception {
        final MetaClientConfig metaClientConfig = new MetaClientConfig();
        metaClientConfig.setDiamondZKDataId("metamorphosis.testZkConfig");
        messageSessionFactory = new MetaMessageSessionFactory(metaClientConfig);
    }


    @After
    public void tearDown() throws Exception {
        messageSessionFactory.shutdown();
    }


    @Test
    public void testGetStats() throws Exception {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setWireFormatType(new MetamorphosisWireFormatType());
        serverConfig.setPort(8199);
        RemotingServer server = RemotingFactory.bind(serverConfig);
        try {
            server.registerProcessor(StatsCommand.class, new RequestProcessor<StatsCommand>() {

                @Override
                public void handleRequest(StatsCommand request, Connection conn) {
                    String rt = "pid 34947\r\n" + //
                            "port 8123\r\n" + //
                            "uptime 3168\r\n" + //
                            "version 1.4.0.3-SNAPSHOT\r\n" + //
                            "curr_connections 1\r\n" + //
                            "threads 34\r\n" + //
                            "cmd_put 0\r\n" + //
                            "cmd_get 0\r\n" + //
                            "cmd_offset 0\r\n" + //
                            "tx_begin 0\r\n" + //
                            "tx_xa_begin 0\r\n" + //
                            "tx_commit 0\r\n" + //
                            "tx_rollback 0\r\n" + //
                            "get_miss 0\r\n" + //
                            "put_failed 0\r\n" + //
                            "total_messages 100051\r\n" + //
                            "topics 1";
                    rt += "\r\nitem " + (StringUtils.isBlank(request.getItem()) ? "null" : request.getItem()) + "\r\n";
                    System.out.println(rt);
                    try {
                        conn.response(new BooleanCommand(200, rt, request.getOpaque()));
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }


                @Override
                public ThreadPoolExecutor getExecutor() {
                    return null;
                }

            });

            String uri = server.getConnectURI().toString();
            messageSessionFactory.getRemotingClient().connect(uri);
            messageSessionFactory.getRemotingClient().awaitReadyInterrupt(uri);
            Map<InetSocketAddress, StatsResult> rt = messageSessionFactory.getStats();
            assertNotNull(rt);
            assertEquals(1, rt.size());
            InetSocketAddress sockAddr =
                    new InetSocketAddress(server.getConnectURI().getHost(), server.getConnectURI().getPort());
            StatsResult sr = rt.get(sockAddr);
            assertStatsResult(sr);
            assertEquals("null", sr.getValue("item"));

            rt = messageSessionFactory.getStats("topics");
            assertNotNull(rt);
            assertEquals(1, rt.size());
            sr = rt.get(sockAddr);
            assertStatsResult(sr);
            assertEquals("topics", sr.getValue("item"));

            sr = messageSessionFactory.getStats(sockAddr);
            assertStatsResult(sr);
            assertEquals("null", sr.getValue("item"));

            sr = messageSessionFactory.getStats(sockAddr, "topics");
            assertStatsResult(sr);
            assertEquals("topics", sr.getValue("item"));
        }
        finally {
            if (server != null) {
                server.stop();
            }
        }
    }


    private void assertStatsResult(StatsResult sr) {
        assertNotNull(sr);
        assertEquals("8123", sr.getValue("port"));
        assertEquals("3168", sr.getValue("uptime"));
        assertEquals("1.4.0.3-SNAPSHOT", sr.getValue("version"));
        assertEquals("1", sr.getValue("curr_connections"));
        assertEquals("0", sr.getValue("cmd_offset"));
        assertEquals("0", sr.getValue("tx_xa_begin"));
        assertEquals("100051", sr.getValue("total_messages"));
        assertEquals("1", sr.getValue("topics"));
        assertEquals(18, sr.getAllValues().size());
    }


    @Test
    public void testCreateProducer() throws Exception {
        final MessageProducer producer = messageSessionFactory.createProducer();
        assertNotNull(producer);
        assertTrue(producer.getPartitionSelector() instanceof RoundRobinPartitionSelector);
        assertFalse(producer.isOrdered());
        assertTrue(messageSessionFactory.getChildren().contains(producer));
        producer.shutdown();
        assertFalse(messageSessionFactory.getChildren().contains(producer));
    }


    @Ignore
    public void testCreateProducerOrdered() throws Exception {
        final MessageProducer producer = messageSessionFactory.createProducer(true);
        assertNotNull(producer);
        assertTrue(producer.getPartitionSelector() instanceof RoundRobinPartitionSelector);
        assertTrue(producer.isOrdered());
        assertTrue(messageSessionFactory.getChildren().contains(producer));
        producer.shutdown();
        assertFalse(messageSessionFactory.getChildren().contains(producer));
    }


    @Test(expected = InvalidConsumerConfigException.class)
    public void testCreateConsumer_NoGroup() throws Exception {
        final ConsumerConfig consumerConfig = new ConsumerConfig();
        final MessageConsumer messageConsumer = messageSessionFactory.createConsumer(consumerConfig);
    }


    @Test(expected = InvalidConsumerConfigException.class)
    public void testCreateConsumer_InvalidThreadCount() throws Exception {
        final ConsumerConfig consumerConfig = new ConsumerConfig();
        consumerConfig.setGroup("test");
        consumerConfig.setFetchRunnerCount(0);
        final MessageConsumer messageConsumer = messageSessionFactory.createConsumer(consumerConfig);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testCreateConsumer_InvalidCommitOffsetsInterval() throws Exception {
        final ConsumerConfig consumerConfig = new ConsumerConfig();
        consumerConfig.setGroup("test");
        consumerConfig.setCommitOffsetPeriodInMills(-1);
        final MessageConsumer messageConsumer = messageSessionFactory.createConsumer(consumerConfig);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testCreateConsumer_InvalidFetchTimeout() throws Exception {
        final ConsumerConfig consumerConfig = new ConsumerConfig();
        consumerConfig.setGroup("test");
        consumerConfig.setFetchTimeoutInMills(0);
        final MessageConsumer messageConsumer = messageSessionFactory.createConsumer(consumerConfig);
    }


    @Test
    public void testCreateConsumer() throws Exception {
        final ConsumerConfig consumerConfig = new ConsumerConfig();
        consumerConfig.setGroup("test");
        final MessageConsumer messageConsumer = messageSessionFactory.createConsumer(consumerConfig);
        assertNotNull(messageConsumer);
        assertTrue(messageSessionFactory.getChildren().contains(messageConsumer));
        messageConsumer.shutdown();
        assertFalse(messageSessionFactory.getChildren().contains(messageConsumer));
    }

}