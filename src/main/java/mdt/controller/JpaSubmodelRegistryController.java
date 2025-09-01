package mdt.controller;

import java.io.IOException;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import utils.stream.FStream;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaMDTSubmodelDescriptor;
import mdt.instance.jpa.JpaMDTSubmodelDescriptorRepository;
import mdt.model.AASUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;

import jakarta.transaction.Transactional;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@Tag(name = "Submodel Registry", description = "MDT 플랫폼 기반 AAS Submodel Registry")
@RestController
@RequestMapping(value={"/aas_registry"})
public class JpaSubmodelRegistryController {
	private final Logger s_logger = LoggerFactory.getLogger(JpaSubmodelRegistryController.class);

	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;
	private final JpaMDTSubmodelDescriptorRepository m_repo;
	
	public JpaSubmodelRegistryController(JpaMDTSubmodelDescriptorRepository repo) {
		m_repo = repo;
	}

    @Operation(summary = "주어진 idShort에 해당하는 모든 Submodel 등록정보들을 반환한다.")
    @Parameters({
    	@Parameter(name = "idShort", description = "검색할 Submodel idShort.")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    		content = {
    			@Content(mediaType = "application/json",
    					array = @ArraySchema(schema=@Schema(implementation = SubmodelDescriptor.class)))
    		}
    	)
    })
    @GetMapping({"/submodel-descriptors"})
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> getAllSubmodelDescriptorsByidShort(
    												@RequestParam(name="idShort", required=false) String idShort,
    												@RequestParam(name="semanticId", required=false) String semanticId)
    	throws SerializationException {
    	List<JpaMDTSubmodelDescriptor> jpaSmDescList;
		if ( idShort != null ) {
			jpaSmDescList = m_repo.findAllByIdShort(idShort);
		}
		else if ( semanticId != null ) {
			jpaSmDescList = m_repo.findAllBySemanticId(semanticId);
		}
		else {
			jpaSmDescList = m_repo.findAll();
		}
		
		List<SubmodelDescriptor> smDescList
					= FStream.from(jpaSmDescList)
							.map(jpaDesc -> {
								String instId = jpaDesc.getInstance().getId();
								String svcEp = m_instanceManager.getInstance(instId).getServiceEndpoint();
								SubmodelDescriptor smDesc = jpaDesc.getAASSubmodelDescriptor();
								if ( svcEp != null ) {
									smDesc = AASUtils.attachEndpoint(smDesc, svcEp);
								}
								return smDesc;
							})
							.toList();
			
		String descListJsjon = MDTModelSerDe.getJsonSerializer().writeList(smDescList);
		return ResponseEntity.ok(descListJsjon);
    }

    @Operation(summary = "주어진 식별자에 해당하는 Submodel 등록정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "submodelId", description = "검색할 Submodel 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelDescriptor.class), mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404", description = "식별자에 해당하는 Submodel이 등록되어 있지 않습니다.")
    })
    @GetMapping(value = "/submodel-descriptors/{submodelId}")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public ResponseEntity<String> getSubmodelDescriptorById(@PathVariable("submodelId") String encodedSmId)
    	throws SerializationException {
		String smId = AASUtils.decodeBase64UrlSafe(encodedSmId);
		
		JpaMDTSubmodelDescriptor jpaSmDesc = m_repo.findBySubmodelId(smId)
												.orElseThrow(() -> new ResourceNotFoundException("SubmodelDescriptor", "id=" + smId));
		SubmodelDescriptor smDesc = jpaSmDesc.getAASSubmodelDescriptor();

		JpaInstanceDescriptor jpaInstDesc = jpaSmDesc.getInstance();
		if ( jpaInstDesc != null ) {
			String svcEp = m_instanceManager.getInstance(jpaInstDesc.getId()).getServiceEndpoint();
			if ( svcEp != null ) {
				smDesc = AASUtils.attachEndpoint(smDesc, svcEp);
			}
		}
    	
    	String descJson = MDTModelSerDe.toJsonString(smDesc);
		return ResponseEntity.ok(descJson);
    }
    
    @Operation(
		summary = "서브모델 관리자에 주어진 서브모델 등록정보를 등록시킨다.",
		description = "현재 MDTManager에서는 Submodel Registry를 통해 Submodel 등록정보 추가를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @Parameters({
    	@Parameter(name = "submodelJson", description = "Json 형식으로 작성된 서브모델 등록 정보"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400",
    				description = "서브모델 등록 정보 파싱에 실패하였거나,"
    							+ "식별자에 해당하는 서브모델 등록정보가 이미 존재합니다."),
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @PostMapping({"/submodel-descriptors"})
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<String> addSubmodelDescriptor(@RequestBody String submodelJson)
    	throws SerializationException, DeserializationException {
    	throw new UnsupportedOperationException();
    }

    @Operation(
		summary = "주어진 식별자에 해당하는 서브모델 등록정보를 삭제시킨다.",
		description = "현재 MDTManager에서는 Submodel Registry를 통해 Submodel 등록정보 삭제를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @Parameters({
    	@Parameter(name = "submodelId", description = "삭제시킬 Submodel의 식별자"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 Submodel 등록 정보가 존재하지 않습니다."),
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @DeleteMapping(value = "/submodel-descriptors/{submodelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSubmodelDescriptor(@PathVariable("submodelId") String submodelId)
    	throws SerializationException {
    	submodelId = AASUtils.decodeBase64UrlSafe(submodelId);
    	throw new UnsupportedOperationException();
    }

    @Operation(
		summary = "주어진 식별자에 해당하는 서브모델 등록정보를 갱신시킨다.",
		description = "현재 MDTManager에서는 Submodel Registry를 통해 Submodel 등록정보 삭제를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @Parameters({
    	@Parameter(name = "submodelJson", description = "변경된 Submodel 등록 정보 (Json 형태)"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 Submodel 등록 정보가 존재하지 않습니다."),
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @PutMapping("/submodel-descriptors")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void updateSubmodelDescriptor(@RequestBody String submodelJson) throws IOException {
    	SubmodelDescriptor smDesc = MDTModelSerDe.readValue(submodelJson, SubmodelDescriptor.class);
    	
    	JpaMDTSubmodelDescriptor desc = m_repo.findBySubmodelId(smDesc.getId())
    												.orElseThrow(() -> new ResourceNotFoundException("SubmodelDescriptor", "id=" + smDesc.getId()));
		desc.updateFrom(smDesc);
    }
}
