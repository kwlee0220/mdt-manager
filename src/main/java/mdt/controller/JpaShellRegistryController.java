package mdt.controller;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.persistence.EntityManagerFactory;
import mdt.MDTController;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.instance.MDTInstance;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping("/shell-registry/shell-descriptors")
public class JpaShellRegistryController extends MDTController<AssetAdministrationShellDescriptor>
										implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(JpaShellRegistryController.class);
	
	@Autowired AbstractInstanceManager m_instanceManager;
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
    @GetMapping("/{aasId}")
    @ResponseStatus(HttpStatus.OK)
    public String getAssetAdministrationShellDescriptorById(@PathVariable("aasId") String aasId)
    	throws SerializationException {
		String decoded = AASUtils.decodeBase64UrlSafe(aasId);
		
		AssetAdministrationShellDescriptor desc = m_jpaProcessor.get(em -> {
			m_instanceManager.setEntityManager(em);
			MDTInstance inst = m_instanceManager.getInstanceByAasId(decoded);
			return inst.getAASDescriptor();
		});
		
		return AASUtils.getJsonSerializer().write(desc);
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
    @GetMapping({""})
    @ResponseStatus(HttpStatus.OK)
    public String getAllAssetAdministrationShellDescriptors(
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

		return AASUtils.getJsonSerializer().write(descList);
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
    @PostMapping({""})
    @ResponseStatus(HttpStatus.CREATED)
    public String addAssetAdministrationShellDescriptor(@RequestBody String aasJson)
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
    @DeleteMapping(value = "/{aasId}")
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
    @PutMapping({""})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateAssetAdministrationShellDescriptor(@RequestBody String aasJson)
    	throws SerializationException, DeserializationException {
		AssetAdministrationShellDescriptor aas = s_deser.read(aasJson,
																AssetAdministrationShellDescriptor.class);
		m_jpaProcessor.run(em -> {
    		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
    		JpaInstanceDescriptor instDesc = instDescMgr.getInstanceDescriptor(aas.getId());
    		instDesc.updateFrom(aas);
		});
    }
}