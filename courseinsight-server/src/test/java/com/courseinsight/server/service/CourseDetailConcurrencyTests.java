package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.dto.CourseUpdateRequest;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseDetailConcurrencyTests {

    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CourseManagementAccessService access = mock(CourseManagementAccessService.class);
    private CourseService service;
    private final Set<Long> firstReads = ConcurrentHashMap.newKeySet();
    private CountDownLatch concurrentMisses;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Map<String, String> values = new ConcurrentHashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenAnswer(call -> {
            String snapshot = values.get(call.getArgument(0));
            if (concurrentMisses != null && firstReads.add(Thread.currentThread().getId())) {
                concurrentMisses.countDown();
                assertThat(concurrentMisses.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return snapshot;
        });
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> values.remove(call.getArgument(0)) != null);
        service = new CourseService(mapper,
                new CourseDetailCache(redis, new ObjectMapper().findAndRegisterModules()),
                mock(CoursePopularityRankingCache.class), access);
    }

    @Test
    void simultaneousMissesShouldLoadCourseOnlyOnce() throws Exception {
        when(mapper.selectById(1L)).thenReturn(course("before"));
        assertConcurrentReads(false);
        verify(mapper, times(1)).selectById(1L);
    }

    @Test
    void simultaneousMissingIdsShouldPopulateNullCacheOnlyOnce() throws Exception {
        when(mapper.selectById(1L)).thenReturn(null);
        assertConcurrentReads(true);
        verify(mapper, times(1)).selectById(1L);
    }

    @Test
    void committedUpdateShouldEvictAfterAnOlderReadFinishesFillingCache() throws Exception {
        CountDownLatch oldRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch updated = new CountDownLatch(1);
        AtomicReference<Course> database = new AtomicReference<>(course("before"));
        when(mapper.selectById(1L)).thenAnswer(call -> {
            Course snapshot = database.get();
            if (oldRead.getCount() == 1) {
                oldRead.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return snapshot;
        });
        when(mapper.updateById(any(Course.class))).thenAnswer(call -> {
            database.set(course("after"));
            updated.countDown();
            return 1;
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = threads.submit(() -> service.getById(1L));
            assertThat(oldRead.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> writer = threads.submit(() -> service.update(11L, UserRole.TEACHER, 1L,
                    new CourseUpdateRequest("CS101", "after", "teacher", null, 1)));
            assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            reader.get(5, TimeUnit.SECONDS);
            writer.get(5, TimeUnit.SECONDS);
            assertThat(service.getById(1L).name()).isEqualTo("after");
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    private void assertConcurrentReads(boolean absent) throws Exception {
        concurrentMisses = new CountDownLatch(8);
        var threads = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < 8; index++) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    if (absent) {
                        assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(ResourceNotFoundException.class);
                    } else {
                        assertThat(service.getById(1L).name()).isEqualTo("before");
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            threads.shutdownNow();
        }
    }

    private Course course(String name) {
        Course course = new Course();
        course.setId(1L);
        course.setCode("CS101");
        course.setName(name);
        course.setTeacherName("teacher");
        course.setStatus(1);
        return course;
    }
}
