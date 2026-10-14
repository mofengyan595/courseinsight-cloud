package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.cache.CourseCacheLookup;
import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.dto.CourseUpdateRequest;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class CourseService {

    // 固定数量的锁，避免任意不存在 ID 使锁表无限增长。
    private final Object[] detailLoadLocks = new Object[256];

    private final CourseMapper courseMapper;
    private final CourseDetailCache courseDetailCache;
    private final CoursePopularityRankingCache popularityRankingCache;
    private final CourseManagementAccessService managementAccessService;

    public CourseService(
            CourseMapper courseMapper,
            CourseDetailCache courseDetailCache,
            CoursePopularityRankingCache popularityRankingCache,
            CourseManagementAccessService managementAccessService) {
        this.courseMapper = courseMapper;
        this.courseDetailCache = courseDetailCache;
        this.popularityRankingCache = popularityRankingCache;
        this.managementAccessService = managementAccessService;
        for (int index = 0; index < detailLoadLocks.length; index++) {
            detailLoadLocks[index] = new Object();
        }
    }

    @Transactional
    public Long create(Long currentUserId, CourseCreateRequest request) {
        Course course = new Course();
        course.setCode(request.code());
        course.setName(request.name());
        course.setTeacherName(request.teacherName());
        course.setOwnerUserId(currentUserId);
        course.setDescription(request.description());
        course.setStatus(1);

        courseMapper.insert(course);
        evictCacheAfterCommit(course.getId());
        return course.getId();
    }

    @Transactional
    public Long update(
            Long currentUserId,
            UserRole currentRole,
            Long id,
            CourseUpdateRequest request) {
        managementAccessService.requireManageableCourse(
                id,
                currentUserId,
                currentRole
        );

        Course course = new Course();
        course.setId(id);
        course.setCode(request.code());
        course.setName(request.name());
        course.setTeacherName(request.teacherName());
        course.setDescription(request.description());
        course.setStatus(request.status());
        if (courseMapper.updateById(course) != 1) {
            throw new ResourceNotFoundException("课程不存在");
        }

        evictCacheAfterCommit(id);
        popularityRankingCache.evictAfterCommit();
        return id;
    }

    public CourseDetailResponse getById(Long id) {
        CourseCacheLookup cacheLookup = courseDetailCache.get(id);
        if (cacheLookup.hit()) {
            return requireCachedCourse(cacheLookup);
        }

        synchronized (detailLoadLock(id)) {
            cacheLookup = courseDetailCache.get(id);
            if (cacheLookup.hit()) {
                return requireCachedCourse(cacheLookup);
            }

            Course course = courseMapper.selectById(id);
            if (course == null) {
                courseDetailCache.putNotFound(id);
                throw new ResourceNotFoundException("课程不存在");
            }

            CourseDetailResponse response = CourseDetailResponse.from(course);
            courseDetailCache.put(id, response);
            return response;
        }
    }

    private CourseDetailResponse requireCachedCourse(CourseCacheLookup cacheLookup) {
        if (cacheLookup.course() == null) {
            throw new ResourceNotFoundException("课程不存在");
        }
        return cacheLookup.course();
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseDetailResponse> page(CoursePageQuery query) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<>();

        if (query.keyword() != null) {
            wrapper.and(condition -> condition
                    .like(Course::getCode, query.keyword())
                    .or()
                    .like(Course::getName, query.keyword())
                    .or()
                    .like(Course::getTeacherName, query.keyword()));
        }

        wrapper.orderByDesc(Course::getCreatedAt)
                .orderByDesc(Course::getId);

        Page<Course> result = courseMapper.selectPage(
                new Page<>(query.page(), query.size()),
                wrapper
        );

        List<CourseDetailResponse> items = result.getRecords()
                .stream()
                .map(CourseDetailResponse::from)
                .toList();

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                items
        );
    }

    private void evictCacheAfterCommit(Long courseId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            evictDetailCache(courseId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evictDetailCache(courseId);
                    }
                }
        );
    }

    private Object detailLoadLock(Long courseId) {
        return detailLoadLocks[Math.floorMod(courseId.hashCode(), detailLoadLocks.length)];
    }

    private void evictDetailCache(Long courseId) {
        // 与本实例的回填互斥，防止提交后删除早于旧查询回填。
        synchronized (detailLoadLock(courseId)) {
            courseDetailCache.evict(courseId);
        }
    }
}
