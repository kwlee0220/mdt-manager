package mdt.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import utils.http.RESTfulErrorEntity;


/**
 * {@link MDTInstanceManagerController}({@code /instance-manager})의 예외 처리. 오류 본문은 {@link RESTfulErrorEntity}
 * ({@code code}=예외 클래스 이름, {@code message})이며, mdt-client의 {@code HttpRESTfulClient}가 이 형식을 읽는다.
 * 상태 코드 규칙은 {@link AbstractMDTExceptionAdvice#toHttpStatus(Throwable)}를 따른다.
 * <p>
 * 2026-11-05 이전에는 컨트롤러 안의 {@code @ExceptionHandler}가 이 일을 했다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestControllerAdvice(assignableTypes = MDTInstanceManagerController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MDTInstanceManagerExceptionAdvice extends AbstractMDTExceptionAdvice {
	@Override
	protected Object toErrorBody(Throwable cause) {
		return RESTfulErrorEntity.of(cause);
	}
}
