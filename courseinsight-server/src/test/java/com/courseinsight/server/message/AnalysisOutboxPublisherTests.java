package com.courseinsight.server.message;

import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.exception.MessageQueueException;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisOutboxPublisherTests {

    @Mock
    private AnalysisOutboxAttemptService attemptService;

    @Mock
    private AnalysisTaskMessageProducer messageProducer;

    @Mock
    private CourseInsightMetrics metrics;

    private final List<AnalysisOutboxPublisher> publishers = new ArrayList<>();

    @AfterEach
    void shutDownPublishers() {
        publishers.forEach(AnalysisOutboxPublisher::shutdown);
    }

    @Test
    void shouldPublishMultipleEventsInParallel() throws Exception {
        AnalysisOutboxPublisher publisher = createPublisher(2);
        List<AnalysisOutboxEvent> events = createEvents(2);
        CountDownLatch bothSending = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        given(attemptService.findPublishable(anyInt(), any(LocalDateTime.class)))
                .willReturn(events);
        given(attemptService.claim(
                anyLong(), anyString(), any(LocalDateTime.class), anyLong()))
                .willReturn(true);
        given(attemptService.markSent(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class)))
                .willReturn(true);
        executeMetricSupplier();
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class)))
                .willAnswer(invocation -> {
                    bothSending.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    AnalysisTaskCreatedEvent event = invocation.getArgument(0);
                    return "message-" + event.taskId();
                });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = caller.submit(publisher::publishPending);
            assertThat(bothSending.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            completed.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }

        verify(messageProducer, times(2)).send(any(AnalysisTaskCreatedEvent.class));
        verify(attemptService, times(2)).markSent(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldNeverExceedConfiguredPublishConcurrency() throws Exception {
        int configuredConcurrency = 2;
        AnalysisOutboxPublisher publisher = createPublisher(configuredConcurrency);
        CountDownLatch firstWaveEntered = new CountDownLatch(configuredConcurrency);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        given(attemptService.findPublishable(anyInt(), any(LocalDateTime.class)))
                .willReturn(createEvents(10));
        given(attemptService.claim(
                anyLong(), anyString(), any(LocalDateTime.class), anyLong()))
                .willReturn(true);
        given(attemptService.markSent(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class)))
                .willReturn(true);
        executeMetricSupplier();
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class)))
                .willAnswer(invocation -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    firstWaveEntered.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        return "message";
                    } finally {
                        active.decrementAndGet();
                    }
                });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = caller.submit(publisher::publishPending);
            assertThat(firstWaveEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(configuredConcurrency);
            assertThat(peak.get()).isEqualTo(configuredConcurrency);
            release.countDown();
            completed.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }

        assertThat(peak.get()).isEqualTo(configuredConcurrency);
        verify(messageProducer, times(10)).send(any(AnalysisTaskCreatedEvent.class));
    }

    @Test
    void shouldMarkSentOnlyForClaimedAttempt() {
        AnalysisOutboxPublisher publisher = createPublisher(2);
        given(attemptService.findPublishable(anyInt(), any(LocalDateTime.class)))
                .willReturn(List.of(createEvent(1)));
        given(attemptService.claim(
                anyLong(), anyString(), any(LocalDateTime.class), anyLong()))
                .willReturn(true);
        given(attemptService.markSent(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class)))
                .willReturn(true);
        executeMetricSupplier();
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class)))
                .willReturn("message-1");

        publisher.publishPending();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(attemptService).claim(
                eq(1L), token.capture(), any(LocalDateTime.class), eq(300L));
        assertThat(token.getValue()).hasSize(32);
        verify(attemptService).markSent(
                eq(1L),
                eq(token.getValue()),
                eq("message-1"),
                any(LocalDateTime.class)
        );
        verify(metrics).outboxPublishSucceeded();
    }

    @Test
    void shouldMarkFailedAndScheduleRetryOnlyForClaimedAttempt() {
        AnalysisOutboxPublisher publisher = createPublisher(2);
        given(attemptService.findPublishable(anyInt(), any(LocalDateTime.class)))
                .willReturn(List.of(createEvent(1)));
        given(attemptService.claim(
                anyLong(), anyString(), any(LocalDateTime.class), anyLong()))
                .willReturn(true);
        given(attemptService.markFailed(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class)))
                .willReturn(true);
        executeMetricSupplier();
        willThrow(new MessageQueueException("RocketMQ unavailable"))
                .given(messageProducer).send(any(AnalysisTaskCreatedEvent.class));

        LocalDateTime before = LocalDateTime.now().plusSeconds(29);
        publisher.publishPending();
        LocalDateTime after = LocalDateTime.now().plusSeconds(31);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> retryAt =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attemptService).claim(
                eq(1L), token.capture(), any(LocalDateTime.class), eq(300L));
        verify(attemptService).markFailed(
                eq(1L),
                eq(token.getValue()),
                eq("RocketMQ unavailable"),
                retryAt.capture()
        );
        assertThat(retryAt.getValue()).isBetween(before, after);
        verify(metrics).outboxPublishFailed();
    }

    @Test
    void shouldSkipEventWhenAnotherPublisherOwnsTheAttempt() {
        AnalysisOutboxPublisher publisher = createPublisher(2);
        given(attemptService.findPublishable(anyInt(), any(LocalDateTime.class)))
                .willReturn(List.of(createEvent(1)));
        given(attemptService.claim(
                anyLong(), anyString(), any(LocalDateTime.class), anyLong()))
                .willReturn(false);

        publisher.publishPending();

        verifyNoInteractions(messageProducer);
    }

    @Test
    void shouldLeavePublishingForRecoveryWhenBrokerAcceptedButMarkSentFailed() {
        AnalysisOutboxPublisher publisher = createPublisher(2);
        AnalysisOutboxEvent event = createEvent(1);
        given(attemptService.findPublishable(anyInt(), any(LocalDateTime.class)))
                .willReturn(List.of(event));
        given(attemptService.claim(
                anyLong(), anyString(), any(LocalDateTime.class), anyLong()))
                .willReturn(true);
        given(attemptService.markSent(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class)))
                .willThrow(new IllegalStateException("database unavailable"))
                .willReturn(true);
        executeMetricSupplier();
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class)))
                .willReturn("message-1", "message-2");

        publisher.publishPending();
        publisher.publishPending();

        verify(messageProducer, times(2)).send(
                org.mockito.ArgumentMatchers.argThat(
                        message -> message.eventId().equals(event.getEventId())
                )
        );
        verify(attemptService, never()).markFailed(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class));
        verify(metrics).outboxPublishSucceeded();
        verify(metrics).outboxPublishFailed();
    }

    @SuppressWarnings("unchecked")
    private void executeMetricSupplier() {
        given(metrics.recordOutboxSend(any(Supplier.class)))
                .willAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });
    }

    private AnalysisOutboxPublisher createPublisher(int concurrency) {
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
                attemptService,
                messageProducer,
                100,
                concurrency,
                30,
                300,
                5,
                metrics
        );
        publishers.add(publisher);
        return publisher;
    }

    private List<AnalysisOutboxEvent> createEvents(int count) {
        List<AnalysisOutboxEvent> events = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            events.add(createEvent(index));
        }
        return events;
    }

    private AnalysisOutboxEvent createEvent(int index) {
        AnalysisOutboxEvent event = new AnalysisOutboxEvent();
        event.setId((long) index);
        event.setEventId(String.format("%032d", index));
        event.setTaskId((long) index);
        event.setCommentId((long) index);
        event.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
        event.setStatus(AnalysisOutboxStatus.PENDING.name());
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return event;
    }
}
