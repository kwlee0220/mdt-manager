package mdt.controller;

import java.io.IOException;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import utils.stream.FStream;

import mdt.instance.AbstractInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.service.MDTInstance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.persistence.EntityManagerFactory;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping(value={"/shell-registry"})
public class JpaShellRegistryController implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(JpaShellRegistryController.class);
	
	@Autowired AbstractInstanceManager<? extends JpaInstance> m_instanceManager;
	@Autowired EntityManagerFactory m_emFact;
	private JpaProcessor m_jpaProcessor;

	@Override
	public void afterPropertiesSet() throws Exception {
		m_jpaProcessor = new JpaProcessor(m_emFact);
		
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("loaded {}", getClass());
		}
	}
	
    @Operation(summary = "주어진 식별자에 해당하는 AssetAdministrationShell 등록정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "aasId", description = "검색할 AssetAdministrationShell 식별자")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = AssetAdministrationShellDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 AssetAdministrationShell이 등록되어 있지 않습니다.")
    })
    @GetMapping("/shell-descriptors/{aasId}")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> getAssetAdministrationShellDescriptorById(@PathVariable("aasId") String aasId)
    	throws SerializationException {
		String decoded = AASUtils.decodeBase64UrlSafe(aasId);
		
		AssetAdministrationShellDescriptor desc = m_jpaProcessor.get(em -> {
			m_instanceManager.setEntityManager(em);
			MDTInstance inst = m_instanceManager.getInstanceByAasId(decoded);
			return inst.getAASDescriptor();
		});
		
		String descJson = MDTModelSerDe.getJsonSerializer().write(desc);
		return ResponseEntity.ok(descJson);
    }
    
    @Operation(summary = "주어진 idShort에 해당하는 모든 AssetAdministrationShell 등록정보들을 반환한다.")
    @Parameters({
    	@Parameter(name = "idShort",
    				description = "검색할 AssetAdministrationShell idShort. "
    						+ "별도로 지정하지 않은 경우는 모든 AssetAdministrationShell 등록정보들을 반환한다.")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    		content = {
    			@Content(mediaType = "application/json",
    					array = @ArraySchema(schema=@Schema(implementation = AssetAdministrationShellDescriptor.class)))
    		}
    	)
    })
    @GetMapping({"/shell-descriptors"})
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> getAllAssetAdministrationShellDescriptors(
    										@RequestParam(name="idShort", required=false) String idShort)
    	throws SerializationException {
    	List<AssetAdministrationShellDescriptor> descList = m_jpaProcessor.get(em -> {
    		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
    		
    		List<JpaInstanceDescriptor> matches;
    		if ( idShort != null ) {
    			matches = instDescMgr.getInstanceDescriptorAllByAasIdShort(idShort);
    		}
    		else {
    			matches = instDescMgr.getInstanceDescriptorAll();
    		}
    		
    		return FStream.from(matches)
	    				.map(d -> d.toAssetAdministrationShellDescriptor())
	    				.toList();
    	});

		String descListJson = MDTModelSerDe.getJsonSerializer().write(descList);
		return ResponseEntity.ok(descListJson);
    }
    
    @GetMapping({"/shell-descriptors/asset/{assetId}"})
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<List<AssetAdministrationShellDescriptor>>
    getAllAssetAdministrationShellDescriptorByAssetId(@PathVariable("assetId") String encodedAssetId)
    	throws SerializationException {
    	String assetId = AASUtils.decodeBase64UrlSafe(encodedAssetId);
    	List<AssetAdministrationShellDescriptor> descList = m_jpaProcessor.get(em -> {
    		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
    		
    		List<JpaInstanceDescriptor> matches = instDescMgr.getInstanceDescriptorAllByAssetId(assetId);
    		return FStream.from(matches)
	    				.map(d -> d.toAssetAdministrationShellDescriptor())
	    				.toList();
    	});

		return ResponseEntity.ok()
							.contentType(MediaType.APPLICATION_JSON)
							.body(descList);
    }

    @Operation(
		summary = "AssetAdministrationShell 관리자에 주어진 AssetAdministrationShell 등록정보를 등록시킨다.",
		description = "현재 MDTManager에서는 AssetAdministrationShell Registry를 통해 AssetAdministrationShell 등록정보 추가를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @Parameters({
    	@Parameter(name = "submodelJson", description = "Json 형식으로 작성된 AssetAdministrationShell 등록 정보"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = SubmodelDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "400",
    				description = "AssetAdministrationShell 등록 정보 파싱에 실패하였거나,"
    							+ "식별자에 해당하는 AssetAdministrationShell 등록정보가 이미 존재합니다."),
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @PostMapping({"/shell-descriptors"})
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<String> addAssetAdministrationShellDescriptor(@RequestBody String aasJson)
    	throws SerializationException, DeserializationException {
    	throw new UnsupportedOperationException();
    }

    @Operation(
		summary = "주어진 식별자에 해당하는 AssetAdministrationShell 등록정보를 삭제시킨다.",
		description = "현재 MDTManager에서는 AssetAdministrationShell Registry를 통해 AssetAdministrationShell 등록정보 삭제를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @Parameters({
    	@Parameter(name = "aasId", description = "삭제시킬 AssetAdministrationShell의 식별자"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = AssetAdministrationShellDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 AssetAdministrationShell 등록 정보가 존재하지 않습니다."),
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @DeleteMapping(value = "/shell-descriptors/{aasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAssetAdministrationShellDescriptor(@PathVariable("aasId") String aasId)
    	throws SerializationException {
    	throw new UnsupportedOperationException();
    }

    @Operation(
		summary = "주어진 식별자에 해당하는 AssetAdministrationShell 등록정보를 갱신시킨다.",
		description = "현재 MDTManager에서는 AssetAdministrationShell Registry를 통해 AssetAdministrationShell 등록정보 삭제를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @Parameters({
    	@Parameter(name = "aasJson", description = "Json 형식으로 작성된 AssetAdministrationShell 등록 정보"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "201", description = "성공",
			content = {
				@Content(schema = @Schema(implementation = AssetAdministrationShellDescriptor.class),
						mediaType = "application/json")
			}),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 AssetAdministrationShell 등록 정보가 존재하지 않습니다."),
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @PutMapping({"/shell-descriptors"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateAssetAdministrationShellDescriptor(@RequestBody String aasJson) throws IOException {
		AssetAdministrationShellDescriptor aas = MDTModelSerDe.readValue(aasJson,
																		AssetAdministrationShellDescriptor.class);
		m_jpaProcessor.run(em -> {
    		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
    		JpaInstanceDescriptor instDesc = instDescMgr.getInstanceDescriptor(aas.getId());
    		instDesc.updateFrom(aas);
		});
    }
}
