package mdt.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;


/**
 * MDTManager 자체 상태 API.
 * <p>
 * 2026-11-05 이전에는 이 컨트롤러가 JVM shutdown hook을 등록해, 새 스레드에서 1초 뒤 인스턴스 매니저를 정리하고
 * {@code System.exit(0)}을 불렀다. hook이 곧바로 반환해서 정리가 사실상 실행되지 않았다. 지금은 인스턴스 매니저의
 * {@code shutdown()}이 {@code @PreDestroy}로 Spring 컨텍스트 종료 때 동기로 불린다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping(value={""})
public class MDTManagerController {
    @Tag(name = "MDTInstance 플랫폼 관리")
    @Operation(summary = "MDTManager의 동작 여부를 확인한다.")
    @Parameters()
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    				content = {@Content(mediaType = "text/plain", schema = @Schema(type = "string"))}
    	)
    })
    @GetMapping("/health")
    @ResponseStatus(HttpStatus.OK)
    public String ping() {
    	return "ok";
    }
}
