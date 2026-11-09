package mdt.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;


/**
 * 컨트롤러가 JSON 문자열을 응답으로 돌려줄 때 쓰는 도우미.
 * <p>
 * {@code ResponseEntity.ok(String)}은 요청의 {@code Accept}에 따라 Content-Type이 {@code text/plain}이 될 수 있으므로
 * JSON임을 명시한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class JsonResponses {
	private JsonResponses() {
		throw new AssertionError();
	}

	/**
	 * 주어진 JSON 문자열을 본문으로 하는 200 응답을 만든다.
	 *
	 * @param json	JSON 문자열.
	 * @return	Content-Type이 {@code application/json}인 응답.
	 */
	static ResponseEntity<String> jsonOk(String json) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
	}
}
