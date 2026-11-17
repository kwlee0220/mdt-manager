package mdt.controller;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fasterxml.jackson.core.JsonProcessingException;

import utils.Throwables;

import mdt.model.InvalidResourceStatusException;
import mdt.model.ModelValidationException;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.expr.MDTParserException;


/**
 * mdt-manager REST 컨트롤러의 공통 예외 처리.
 * <p>
 * 예외를 {@link #toHttpStatus(Throwable)}의 규칙 하나로 HTTP 상태에 대응시키고, 응답 본문 형식만 하위 클래스가
 * 정한다({@link #toErrorBody(Throwable)}). Spring MVC 표준 예외(요청 파라미터·파트 누락, 본문 파싱 실패, 지원하지
 * 않는 메소드, 없는 경로 등)는 {@link ResponseEntityExceptionHandler}가 원래의 4xx 상태로 처리한다.
 * 5xx 응답은 ERROR 로그를 남긴다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractMDTExceptionAdvice extends ResponseEntityExceptionHandler {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractMDTExceptionAdvice.class);
	
	/**
	 * 오류 응답 본문을 만든다.
	 *
	 * @param cause	포장을 벗긴 예외.
	 * @return	응답 본문 객체.
	 */
	protected abstract Object toErrorBody(Throwable cause);
	
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> handleMDTException(Exception e) {
		Throwable cause = Throwables.unwrapThrowable(e);
		HttpStatus status = toHttpStatus(cause);
		if ( status.is5xxServerError() ) {
			s_logger.error("Internal server error: status={}", status.value(), cause);
		}
		return ResponseEntity.status(status)
							.contentType(MediaType.APPLICATION_JSON)
							.body(toErrorBody(cause));
	}
	
	/**
	 * 예외(포장을 벗긴 것)에 대응하는 HTTP 상태를 반환한다.
	 * <ul>
	 *   <li>{@link ResourceNotFoundException}: 404</li>
	 *   <li>{@link ResourceAlreadyExistsException}, {@link InvalidResourceStatusException}: 409</li>
	 *   <li>{@link IllegalArgumentException}, {@link MDTParserException}(참조 표현식 문법 오류),
	 *   	{@link ModelValidationException}(업로드한 모델의 구조 오류),
	 *   	요청 JSON 파싱 실패({@link JsonProcessingException}, AAS4J {@link DeserializationException}을 원인으로 가진
	 *   	{@link IOException}): 400</li>
	 *   <li>{@link AccessDeniedException}: 403</li>
	 *   <li>{@link TimeoutException}: 408</li>
	 *   <li>{@link UnsupportedOperationException}: 501</li>
	 *   <li>그 밖: 500</li>
	 * </ul>
	 *
	 * @param cause	예외.
	 * @return	HTTP 상태.
	 */
	public static HttpStatus toHttpStatus(Throwable cause) {
		if ( cause instanceof ResourceNotFoundException ) {
			return HttpStatus.NOT_FOUND;
		}
		else if ( cause instanceof ResourceAlreadyExistsException || cause instanceof InvalidResourceStatusException ) {
			return HttpStatus.CONFLICT;
		}
		else if ( cause instanceof IllegalArgumentException || cause instanceof MDTParserException
				|| cause instanceof ModelValidationException
				|| isRequestParseFailure(cause) ) {
			return HttpStatus.BAD_REQUEST;
		}
		else if ( cause instanceof AccessDeniedException ) {
			return HttpStatus.FORBIDDEN;
		}
		else if ( cause instanceof TimeoutException ) {
			return HttpStatus.REQUEST_TIMEOUT;
		}
		else if ( cause instanceof UnsupportedOperationException ) {
			return HttpStatus.NOT_IMPLEMENTED;
		}
		else {
			return HttpStatus.INTERNAL_SERVER_ERROR;
		}
	}
	
	private static boolean isRequestParseFailure(Throwable cause) {
		return cause instanceof JsonProcessingException
			|| (cause instanceof IOException && cause.getCause() instanceof DeserializationException);
	}
}
