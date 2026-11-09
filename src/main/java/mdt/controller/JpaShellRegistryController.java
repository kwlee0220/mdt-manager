package mdt.controller;

import static mdt.controller.JsonResponses.jsonOk;

import java.io.IOException;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
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

import utils.Preconditions;
import utils.stream.FStream;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.model.AASUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstance;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Tag(name = "Shell Registry", description = "MDT 플랫폼 기반 AAS Shell Registry")
@RestController
@RequestMapping(value={"/aas_registry"})
public class JpaShellRegistryController implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(JpaShellRegistryController.class);

	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;

	@Override
	public void afterPropertiesSet() throws Exception {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("loaded {}", getClass());
		}
	}
	
    @Operation(summary = "주어진 식별자에 해당하는 AssetAdministrationShell 등록정보를 반환한다.")
    @Parameters({
    	@Parameter(name = "aasId", description = "검색할 AssetAdministrationShell 식별자 (Base64URL 인코딩)")
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

		MDTInstance inst = m_instanceManager.getInstanceByAasId(decoded);
		AssetAdministrationShellDescriptor desc = inst.getAASShellDescriptor();
		
		String descJson = MDTModelSerDe.getJsonSerializer().write(desc);
		return jsonOk(descJson);
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
		List<? extends MDTInstance> instList = (idShort != null)
												? m_instanceManager.getInstanceAllByAasIdShort(idShort)
												: m_instanceManager.getInstanceAll();
    	List<AssetAdministrationShellDescriptor> descList
    		= FStream.from(instList)
		    		.map(inst -> inst.getAASShellDescriptor())
		    		.toList();

		String descListJson = MDTModelSerDe.getJsonSerializer().write(descList);
		return jsonOk(descListJson);
    }
    
    @Operation(summary = "주어진 Asset 식별자에 해당하는 모든 AssetAdministrationShell 등록정보들을 반환한다.")
    @Parameters({
    	@Parameter(name = "assetId", description = "검색할 Asset 식별자 (Base64URL 인코딩)")
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "200", description = "성공",
    		content = {
    			@Content(mediaType = "application/json",
    					array = @ArraySchema(schema=@Schema(implementation = AssetAdministrationShellDescriptor.class)))
    		}
    	)
    })
    @GetMapping({"/shell-descriptors/asset/{assetId}"})
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String>
    getAllAssetAdministrationShellDescriptorByAssetId(@PathVariable("assetId") String encodedAssetId)
    	throws SerializationException {
    	String assetId = AASUtils.decodeBase64UrlSafe(encodedAssetId);
    	
    	List<? extends MDTInstance> matches = m_instanceManager.getInstanceAllByAssetId(assetId);
    	List<AssetAdministrationShellDescriptor> descList = FStream.from(matches)
														    		.map(inst -> inst.getAASShellDescriptor())
														    		.toList();
		// AAS JSON 직렬화기로 쓴다(2026-11-05 이전에는 Spring 기본 매퍼, 검토 D32).
		return jsonOk(MDTModelSerDe.getJsonSerializer().writeList(descList));
    }

    @Operation(
		summary = "AssetAdministrationShell 관리자에 주어진 AssetAdministrationShell 등록정보를 등록시킨다.",
		description = "현재 MDTManager에서는 AssetAdministrationShell Registry를 통해 AssetAdministrationShell 등록정보 추가를"
					+ "지원하지 않기 때문에 호출시 항상 501 오류를 발생시킨다."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	required = true,
    	description = "Json 형식으로 작성된 AssetAdministrationShell 등록 정보",
		content = {
			@Content(schema = @Schema(implementation = AssetAdministrationShellDescriptor.class),
					mediaType = "application/json")
		}
	)
    @ApiResponses(value = {
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
    	@Parameter(name = "aasId", description = "삭제시킬 AssetAdministrationShell의 식별자 (Base64URL 인코딩)"),
    })
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "501", description = "연산이 구현되지 않았습니다.")
    })
    @DeleteMapping(value = "/shell-descriptors/{aasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAssetAdministrationShellDescriptor(@PathVariable("aasId") String aasId)
    	throws SerializationException {
    	throw new UnsupportedOperationException();
    }

    @Operation(
		summary = "주어진 AssetAdministrationShell 등록정보로 기존 등록정보를 갱신시킨다.",
		description = "요청 본문에 포함된 AssetAdministrationShell 식별자에 해당하는 등록정보를 갱신한다."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
    	required = true,
    	description = "Json 형식으로 작성된 갱신할 AssetAdministrationShell 등록 정보",
		content = {
			@Content(schema = @Schema(implementation = AssetAdministrationShellDescriptor.class),
					mediaType = "application/json")
		}
	)
    @ApiResponses(value = {
    	@ApiResponse(responseCode = "204", description = "성공"),
    	@ApiResponse(responseCode = "400",
    				description = "AssetAdministrationShell 등록 정보 파싱에 실패하였습니다."),
    	@ApiResponse(responseCode = "404",
    				description = "식별자에 해당하는 AssetAdministrationShell 등록 정보가 존재하지 않습니다.")
    })
    @PutMapping({"/shell-descriptors"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateAssetAdministrationShellDescriptor(@RequestBody String aasJson) throws IOException {
		AssetAdministrationShellDescriptor aas = MDTModelSerDe.readValue(aasJson,
																		AssetAdministrationShellDescriptor.class);
		Preconditions.checkArgument(aas.getId() != null, "AssetAdministrationShellDescriptor id is null");
		
		// 본문의 id는 AAS id다. 그 AAS를 가진 인스턴스를 찾아 갱신한다(없으면 404). 2026-11-05 이전에는 AAS id를
		// instance id로 넘겨, 두 id가 다르면 항상 404였다.
		String instId = m_instanceManager.getInstanceByAasId(aas.getId()).getId();
		m_instanceManager.updateInstanceDescriptor(instId, desc -> desc.updateFrom(aas));
    }
}
