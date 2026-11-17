package mdt.controller;

import org.springframework.web.bind.annotation.RestControllerAdvice;

import mdt.client.Fa3stMessage;


/**
 * {@link MDTInstanceManagerController}를 뺀 컨트롤러(registry {@code /aas_registry}, {@code /list}, {@code /health})의
 * 예외 처리. 오류 본문은 {@link Fa3stMessage}({@code code}=예외 클래스 이름, {@code text})이며, mdt-client의
 * {@code HttpRegistryClient}가 이 형식을 읽어 {@code code}의 예외 타입을 되살린다(그래서 본문 형식을 바꾸지 않는다).
 * 상태 코드 규칙은 {@link AbstractMDTExceptionAdvice#toHttpStatus(Throwable)}를 따른다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestControllerAdvice
public class MDTExceptionAdvice extends AbstractMDTExceptionAdvice {
	@Override
	protected Object toErrorBody(Throwable cause) {
		return Fa3stMessage.from(cause);
	}
}
