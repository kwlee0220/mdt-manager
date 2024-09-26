package mdt.controller;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import mdt.MDTController;
import mdt.instance.jpa.JpaInstanceSubmodelDescriptor;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.ResourceNotFoundException;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
@RestController
@RequestMapping("/submodel-registry/submodel-descriptors")
public class JpaSubmodelRegistryController extends MDTController<SubmodelDescriptor>
											implements InitializingBean {
	private final Logger s_logger = LoggerFactory.getLogger(JpaSubmodelRegistryController.class);

	@Autowired EntityManagerFactory m_emFact;
	private JpaProcessor m_jpaProcessor;

	@Override
	public void afterPropertiesSet() throws Exception {
		m_jpaProcessor = new JpaProcessor(m_emFact);
		
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("loaded {}", getClass());
		}
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
    @GetMapping({""})
    @ResponseStatus(HttpStatus.OK)
    public String getAllSubmodelDescriptorsByidShort(@RequestParam(name="idShort", required=false) String idShort)
    	throws SerializationException {
    	String jpql = ( idShort != null )
    				? String.format("select s from JpaInstanceSubmodelDescriptor s "
    								+ "where s.idShort = '%s'", idShort)
    				: "select s from JpaInstanceSubmodelDescriptor s";
		List<SubmodelDescriptor> smDescList = m_jpaProcessor.get(em -> {
			TypedQuery<JpaInstanceSubmodelDescriptor> query
											= em.createQuery(jpql, JpaInstanceSubmodelDescriptor.class);
			return query.getResultStream()
						.map(d -> d.getInstance().toSubmodelDescriptor(d))
						.toList();
    	});
		return s_ser.writeList(smDescList);
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
    @GetMapping(value = "/{submodelId}")
    @ResponseStatus(HttpStatus.OK)
    public String getSubmodelDescriptorById(@PathVariable("submodelId") String submodelId)
    	throws SerializationException {
		String decodedId = AASUtils.decodeBase64UrlSafe(submodelId);
		
    	String jpql = String.format("select s from JpaInstanceSubmodelDescriptor s "
    								+ "where s.id = '%s'", decodedId);
    	SubmodelDescriptor smDesc = m_jpaProcessor.get(em -> {
			try {
				TypedQuery<JpaInstanceSubmodelDescriptor> query
												= em.createQuery(jpql, JpaInstanceSubmodelDescriptor.class);
				JpaInstanceSubmodelDescriptor ismDesc = query.getSingleResult();
				return ismDesc.getInstance().toSubmodelDescriptor(ismDesc);
			}
			catch ( NoResultException e ) {
				throw new ResourceNotFoundException("SubmodelDescriptor", "id=" + decodedId);
			}
    	});
		return s_ser.write(smDesc);
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
    @PostMapping({""})
    @ResponseStatus(HttpStatus.CREATED)
    public String addSubmodelDescriptor(@RequestBody String submodelJson)
    	throws SerializationException, DeserializationException {
//		SubmodelDescriptor aas = s_deser.read(submodelJson, SubmodelDescriptor.class);
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
    @DeleteMapping(value = "/{submodelId}")
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
    @PutMapping("")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSubmodelDescriptor(@RequestBody String submodelJson)
    	throws SerializationException, DeserializationException {
    	SubmodelDescriptor smDesc = s_deser.read(submodelJson, SubmodelDescriptor.class);

    	String jpql = String.format("select s from JpaInstanceSubmodelDescriptor s where s.id = '%s'",
    								smDesc.getId());
    	m_jpaProcessor.run(em -> {
			TypedQuery<JpaInstanceSubmodelDescriptor> query
											= em.createQuery(jpql, JpaInstanceSubmodelDescriptor.class);
			JpaInstanceSubmodelDescriptor desc = query.getSingleResult();
			desc.updateFrom(smDesc);
    	});
    }
}
