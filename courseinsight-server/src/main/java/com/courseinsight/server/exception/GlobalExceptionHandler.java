package com.courseinsight.server.exception;

import com.courseinsight.server.common.ApiResponse;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("请求参数错误");

        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException exception) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(), "课程代码已存在");
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleUsernameAlreadyExists(
            UsernameAlreadyExistsException exception) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(DuplicateCommentException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateComment(DuplicateCommentException exception) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleInvalidCredentials(
            InvalidCredentialsException exception) {
        return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), exception.getMessage());
    }

    @ExceptionHandler(UserDisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleUserDisabled(UserDisabledException exception) {
        return ApiResponse.error(HttpStatus.FORBIDDEN.value(), exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(ResourceNotFoundException exception) {
        return ApiResponse.error(HttpStatus.NOT_FOUND.value(), exception.getMessage());
    }

    @ExceptionHandler(AnalysisTaskConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleTaskConflict(AnalysisTaskConflictException exception) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(UserRoleConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleUserRoleConflict(UserRoleConflictException exception) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(CourseAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleCourseAccessDenied(CourseAccessDeniedException exception) {
        return ApiResponse.error(HttpStatus.FORBIDDEN.value(), exception.getMessage());
    }

    @ExceptionHandler(AiServiceException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleAiService(AiServiceException exception) {
        return ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), exception.getMessage());
    }

    @ExceptionHandler(MessageQueueException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleMessageQueue(MessageQueueException exception) {
        return ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getMessage());
    }
}
