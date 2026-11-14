package mdt.controller;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import utils.http.RESTfulErrorEntity;
import utils.stream.FStream;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.AbstractJpaInstanceManager.SubmodelKey;
import mdt.instance.JpaInstance;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ResourceNotFoundException;
import mdt.model.SubmodelService;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTOperationDescriptor.ArgumentDescriptor;


/**
 * 명령행 도구의 자동 완성에 쓰는 후보 목록 API({@code /list}). 결과는 줄마다 후보 하나인 {@code text/plain}이다.
 * <p>
 * {@code /list/expr}는 MDT 표현식의 앞부분을 받아 완성 후보를 돌려준다(규칙은 {@link ExpressionCompleter}).
 * 나머지는 MDTInstance 식별자, 파라미터, 연산, 인자, Submodel, 시계열, element 경로 같은 개별 식별자 목록이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Tag(name = "후보 식별자 목록 조회",
	description = "MDT 모델 관련 식별자 후보 목록을 조회한다. 주로 명령행 도구의 자동 완성에 사용된다.")
@RestController
@RequestMapping(value={"/list"})
public class MDTListCandidatesController {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTListCandidatesController.class);
	
	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;
	private final ExpressionCompleter m_completer = new ExpressionCompleter(new InstanceManagerSource());

    @Operation(summary = "MDT 표현식의 앞부분에 대한 자동 완성 후보 목록을 반환한다.",
    		description = "후보는 입력한 앞부분 전체로 시작하는 완전한 표현식이며, 다음 단계가 있으면 구분자"
    				+ "(':', '.', '[')로 끝난다. 다루는 형태: param:, oparg:, opvar:, timeseries:, "
    				+ "submodel:id=, <instance>:<submodel>:<path>. 식별자 후보는 실행 중이 아닌 MDTInstance도 "
    				+ "포함하고, element 경로 후보는 실행 중인 MDTInstance에서만 만든다. 해석할 수 없는 입력은 "
    				+ "오류 없이 빈 목록이다. 후보에 ':'가 들어가므로 bash 자동 완성에서는 COMP_WORDBREAKS의 ':'를 "
    				+ "처리해야 한다(예: __ltrim_colon_completions).")
    @Parameters({
    	@Parameter(name = "prefix", description="입력 중인 MDT 표현식 앞부분. 생략하면 빈 문자열.",
    				example="param:CNC-04:")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 후보 목록(최대 "
    				+ ExpressionCompleter.MAX_CANDIDATES + "개).",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			})
    })
	@GetMapping(value="/expr", produces="text/plain")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> listExpressionCandidates(@RequestParam(name="prefix", defaultValue="")
    														String prefix) {
		List<String> candidates;
		try {
			candidates = m_completer.complete(prefix);
		}
		catch ( RuntimeException e ) {
			// 자동 완성은 입력 중인 미완성 표현식을 다루므로 실패를 오류로 돌려주지 않는다.
			s_logger.debug("failed to list expression candidates: prefix={}, cause={}", prefix, e.toString());
			candidates = List.of();
		}
		return ResponseEntity.ok(String.join(System.lineSeparator(), candidates));
	}

    @Operation(summary = "주어진 유형에 해당하는 MDTInstance 식별자 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "type", description="검색할 MDTInstance 유형. "
    						+ "'all' (전체), 'running' (실행 중), 'non-running' (실행 중이 아님) 중 하나.",
    				example="running")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 MDTInstance 식별자 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			}),
    	@ApiResponse(responseCode="400", description="지원하지 않는 MDTInstance 유형인 경우.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/ids/{type}")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listInstanceIds(@PathVariable("type") String type) {
		String output = FStream.from(m_instanceManager.listInstanceIds(type))
								.join(System.lineSeparator());
    	return ResponseEntity.ok(output);
    }

    @Operation(summary = "MDTInstance에 정의된 Parameter 식별자 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "instance", description="대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 Parameter 식별자 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			}),
    	@ApiResponse(responseCode="404", description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/parameters")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateParameters(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listParameterIds(instId))
								.join(System.lineSeparator());
    	return ResponseEntity.ok(output);
    }

    @Operation(summary = "MDTInstance에 정의된 연산 (AI/Simulation) 식별자 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "instance", description="대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 연산 식별자 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			}),
    	@ApiResponse(responseCode="404", description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/operations")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateOperations(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listOperationIds(instId))
								.join(System.lineSeparator());
    	return ResponseEntity.ok(output);		
    }

    @Operation(summary = "MDTInstance의 연산에 정의된 인자 식별자 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "instance", description="대상 MDTInstance 식별자"),
    	@Parameter(name = "operation", description="대상 연산 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 인자 식별자 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			}),
    	@ApiResponse(responseCode="404", description="식별자에 해당하는 MDTInstance 또는 연산이 등록되어 있지 않습니다.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/arguments")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateArguments(@RequestParam(name="instance") String instId,
    												@RequestParam(name="operation") String opId) {
		String output = FStream.from(m_instanceManager.listArgumentIds(instId, opId))
								.join(System.lineSeparator());
    	return ResponseEntity.ok(output);
    }

    @Operation(summary = "MDTInstance에 포함된 Submodel 식별자 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "instance", description="대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 Submodel 식별자 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			}),
    	@ApiResponse(responseCode="404", description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/submodels")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateSubmodels(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listSubmodelIds(instId))
								.join(System.lineSeparator());
    	return ResponseEntity.ok(output);
    }

    @Operation(summary = "MDTInstance에 정의된 시계열(TimeSeries) 식별자 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "instance", description="대상 MDTInstance 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 시계열 식별자 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			}),
    	@ApiResponse(responseCode="404", description="식별자에 해당하는 MDTInstance가 등록되어 있지 않습니다.",
			content = {
				@Content(schema = @Schema(implementation=RESTfulErrorEntity.class), mediaType="application/json")
			})
    })
    @GetMapping("/timeseries")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateTimeSeries(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listTimeSeriesIds(instId))
								.join(System.lineSeparator());
    	return ResponseEntity.ok(output);
    }

    @Operation(summary = "MDTInstance의 Submodel에 포함된 SubmodelElement의 idShort 경로 후보 목록을 반환한다.",
    		description = "'path'가 지정되면 해당 경로를 접두어로 갖는 하위 element 경로 후보들을 반환한다. "
    				+ "MDTInstance 또는 Submodel이 존재하지 않는 경우에는 빈 목록을 반환한다.")
    @Parameters({
    	@Parameter(name = "instance", description="대상 MDTInstance 식별자"),
    	@Parameter(name = "submodel", description="대상 Submodel의 idShort"),
    	@Parameter(name = "path", description="검색 기준 idShort 경로 접두어. 생략하면 최상위 element들이 검색된다.")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode="200", description="성공. 줄 단위로 구분된 idShort 경로 목록.",
			content = {
				@Content(schema = @Schema(implementation=String.class), mediaType="text/plain")
			})
    })
    @GetMapping("/elements")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateElements(@RequestParam(name="instance") String instId,
    												@RequestParam(name="submodel") String smIdShort,
    												@RequestParam(name="path", required=false) String path) {
    	JpaInstance inst;
    	try {
    		inst = m_instanceManager.getInstance(instId);
    	}
    	catch ( ResourceNotFoundException e ) {
    		// 없는 인스턴스만 빈 목록이다. DB 오류 등은 숨기지 않는다.
    		return ResponseEntity.ok("");
    	}
		
		SubmodelService svc = inst.getSubmodelServiceByIdShort(smIdShort).getOrNull();
		if ( svc == null ) {
			return ResponseEntity.ok("");
		}
		
		List<String> paths = SubmodelElementCandidates.list(svc, path, null);
		return ResponseEntity.ok(String.join(System.lineSeparator(), paths));
    }

	/**
	 * {@link ExpressionCompleter}가 쓰는 조회를 MDTInstance 관리자로 구현한다.
	 */
	private final class InstanceManagerSource implements ExpressionCompleter.Source {
		@Override
		public List<String> instanceIds() {
			return m_instanceManager.listInstanceIds("all");
		}

		@Override
		public List<String> parameterIds(String instanceId) {
			return m_instanceManager.listParameterIds(instanceId);
		}

		@Override
		public List<String> operationSubmodels(String instanceId) {
			return m_instanceManager.listOperationIds(instanceId);
		}

		@Override
		public List<String> argumentIds(String instanceId, String operationSubmodel, String kind) {
			// 인자 참조는 'oparg:<instance>:<submodel>:(in|out):<argumentId>' 형식이다.
			String refPrefix = String.format("oparg:%s:%s:%s:", instanceId, operationSubmodel, kind);
			return FStream.from(m_instanceManager.listArgumentDescriptors(instanceId, operationSubmodel))
							.filter(desc -> desc.getReference().startsWith(refPrefix))
							.map(ArgumentDescriptor::getId)
							.toList();
		}

		@Override
		public List<String> submodelIdShorts(String instanceId) {
			return m_instanceManager.listSubmodelIds(instanceId);
		}

		@Override
		public List<String> timeSeriesSubmodels(String instanceId) {
			return m_instanceManager.listTimeSeriesIds(instanceId);
		}

		@Override
		public List<SubmodelKey> submodels() {
			return m_instanceManager.listSubmodelKeys();
		}

		@Override
		public Optional<SubmodelService> runningSubmodelService(String instanceId, String submodelIdShort) {
			try {
				JpaInstance inst = m_instanceManager.getInstance(instanceId);
				if ( inst.getStatus() != MDTInstanceStatus.RUNNING ) {
					return Optional.empty();
				}
				return Optional.ofNullable(inst.getSubmodelServiceByIdShort(submodelIdShort).getOrNull());
			}
			catch ( ResourceNotFoundException | InvalidResourceStatusException e ) {
				return Optional.empty();
			}
		}
	}
}
