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
 * 이 컨트롤러는 종료 처리를 하지 않는다. 인스턴스 매니저의 {@code shutdown()}은 {@code @PreDestroy}로
 * Spring 컨텍스트 종료 때 동기로 불린다.
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
