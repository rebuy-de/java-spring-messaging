/**
 * Copyright 2017-2018 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.spring.integration.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;

import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class KafkaBinderTest {

  @ClassRule
  public static KafkaEmbedded embeddedKafka = new KafkaEmbedded(1);

  @Autowired
  private Sender sender;

  @Autowired
  private Receiver receiver;

  @Autowired
  private MockTracer tracer;

  @BeforeClass
  public static void setup() {
    System.setProperty("spring.kafka.bootstrap-servers", embeddedKafka.getBrokersAsString());
    System.setProperty("spring.cloud.stream.kafka.binder.zkNodes", embeddedKafka.getZookeeperConnectionString());
  }

  @Test
  public void testFlowFromSourceToSink() {
    sender.send("Ping");

    await().atMost(5, SECONDS)
        .until(receiver::getReceivedMessages, hasSize(1));

    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertThat(finishedSpans).hasSize(2);

    MockSpan outputSpan = getSpanByOperation("send:output");
    assertThat(outputSpan.parentId()).isEqualTo(0);
    assertThat(outputSpan.tags()).hasSize(3);
    assertThat(outputSpan.tags()).containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_PRODUCER);
    assertThat(outputSpan.tags()).containsEntry(Tags.COMPONENT.getKey(), OpenTracingChannelInterceptor.COMPONENT_NAME);
    assertThat(outputSpan.tags()).containsEntry(Tags.MESSAGE_BUS_DESTINATION.getKey(), "output");

    MockSpan inputSpan = getSpanByOperation("send:input");
    assertThat(inputSpan.parentId()).isEqualTo(0);
    assertThat(inputSpan.tags()).hasSize(3);
    assertThat(inputSpan.tags()).containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_PRODUCER);
    assertThat(inputSpan.tags()).containsEntry(Tags.COMPONENT.getKey(), OpenTracingChannelInterceptor.COMPONENT_NAME);
    assertThat(inputSpan.tags()).containsEntry(Tags.MESSAGE_BUS_DESTINATION.getKey(), "input");

    assertThat(outputSpan.startMicros()).isLessThanOrEqualTo(inputSpan.startMicros());
  }

  private MockSpan getSpanByOperation(String operationName) {
    return tracer.finishedSpans()
        .stream()
        .filter(s -> operationName.equals(s.operationName()))
        .findAny()
        .orElseThrow(
            () -> new RuntimeException(String.format("Span for operation '%s' doesn't exist", operationName)));
  }

}