package mdt.controller;

import java.util.Collections;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
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

import utils.Utilities;
import utils.http.RESTfulErrorEntity;
import utils.func.Try;
import utils.stream.FStream;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.model.SubmodelService;
import mdt.model.sm.SubmodelUtils;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@Tag(name = "후보 식별자 목록 조회",
	description = "MDT 모델 관련 식별자 후보 목록을 조회한다. 주로 명령행 도구의 자동 완성에 사용된다.")
@RestController
@RequestMapping(value={"/list"})
public class MDTListCandidatesController {
	private final Logger s_logger = LoggerFactory.getLogger(MDTListCandidatesController.class);
	
	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;

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
								.join(Utilities.getLineSeparator());
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
								.join(Utilities.getLineSeparator());
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
								.join(Utilities.getLineSeparator());
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
								.join(Utilities.getLineSeparator());
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
								.join(Utilities.getLineSeparator());
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
								.join(Utilities.getLineSeparator());
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
    	JpaInstance inst = Try.get(() -> m_instanceManager.getInstance(instId))
    							.getOrNull();
		if ( inst == null ) {
			return ResponseEntity.ok("");
		}
		
		SubmodelService svc = inst.getSubmodelServiceByIdShort(smIdShort).getOrNull();
		if ( svc == null ) {
			return ResponseEntity.ok("");
		}
		
		String output = listCandidateElements(svc, path).join(Utilities.getLineSeparator());
		return ResponseEntity.ok(output);
		
//		String parentPath;
//		List<String> suffixes;
//		if ( path.isEmpty() ) {
//			parentPath = "";
//			suffixes = listChildren(svc, parentPath, "");
//		}
//		else if ( path.endsWith(".") || path.endsWith("[") ) {
//			parentPath = path.substring(0, path.length()-1);
//			suffixes = listChildren(svc, parentPath, "");
//		}
//		else {
//			List<String> pathSegs = SubmodelUtils.parseIdShortPath(path).toList();
//			parentPath = SubmodelUtils.buildIdShortPath(pathSegs.subList(0, pathSegs.size()-1));
//			suffixes = listChildren(svc, parentPath, pathSegs.get(pathSegs.size()-1));
//		}
//		
//		String output = FStream.from(suffixes)
//								.map(suffix -> (parentPath.isEmpty()) ? suffix.substring(1)
//																		: parentPath + suffix)
//								.join(Utilities.getLineSeparator());
//    	return ResponseEntity.ok(output);
    }

    private FStream<String> listCandidateElements(SubmodelService svc, String path) {
		String parentPath;
		List<String> suffixes;
		if ( path == null || path.isEmpty() ) {
			parentPath = "";
			suffixes = listChildren(svc, parentPath, "");
		}
		else if ( path.endsWith(".") || path.endsWith("[") ) {
			parentPath = path.substring(0, path.length()-1);
			suffixes = listChildren(svc, parentPath, "");
		}
		else {
			List<String> pathSegs = SubmodelUtils.parseIdShortPath(path).toList();
			parentPath = SubmodelUtils.buildIdShortPath(pathSegs.subList(0, pathSegs.size()-1));
			suffixes = listChildren(svc, parentPath, pathSegs.get(pathSegs.size()-1));
		}
		
		return FStream.from(suffixes)
						.map(suffix -> (parentPath.isEmpty()) ? suffix.substring(1)
																: parentPath + suffix);
    }
    
    private List<String> listChildren(SubmodelService svc, String parentPath, String prefix) {
    	SubmodelElement sme = ( !parentPath.isEmpty() )
    						? svc.getSubmodelElementByPath(parentPath) : null;
    	if ( sme == null ) {
    		return listCollectionChildren(svc.getSubmodel().getSubmodelElements(), prefix);
    	}
    	else if ( sme instanceof SubmodelElementCollection smc ) {
    		return listCollectionChildren(smc.getValue(), prefix);
    	}
    	else if ( sme instanceof SubmodelElementList sml ) {
    		if ( sml.getValue().size() == 0 ) {
        		return Collections.emptyList();
    		}
    		
    		SubmodelElement proto = sml.getValue().get(0);
    		if ( prefix.length() > 0 ) {
    			int idx = Integer.parseInt(prefix);
    			return listChildCandidates(proto, "", "[" + idx + "]");
    		}
    		else {
				return FStream.range(0, sml.getValue().size())
								.flatMapIterable(idx -> listChildCandidates(proto, "", "[" + idx + "]"))
								.toList();
    		}
    	}
    	else {
    		return Collections.emptyList();
    	}
    }
    
	private List<String> listCollectionChildren(List<SubmodelElement> children, String prefix) {
		return FStream.from(children)
						.filter(sme -> sme.getIdShort().startsWith(prefix))
						.flatMapIterable(sme -> listChildCandidates(sme, ".", sme.getIdShort()))
						.toList();
	}
	
	private List<String> listChildCandidates(SubmodelElement child, String delim, String title) {
		if ( child instanceof SubmodelElementCollection ) {
			return List.of(delim + title, delim + title + ".");
		}
    	else if ( child instanceof SubmodelElementList ) {
			return List.of(delim + title, delim + title + "[");
    	}
    	else {
    		return List.of(delim + title);
    	}
	}
}
