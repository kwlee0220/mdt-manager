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

import utils.Utilities;
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
@RestController
@RequestMapping(value={"/list"})
public class MDTListCandidatesController {
	private final Logger s_logger = LoggerFactory.getLogger(MDTListCandidatesController.class);
	
	@Autowired AbstractJpaInstanceManager<? extends JpaInstance> m_instanceManager;

    @GetMapping("/ids/{type}")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listInstanceIds(@PathVariable("type") String type) {
		String output = FStream.from(m_instanceManager.listInstanceIds(type))
								.join(Utilities.getLineSeparator());
    	return ResponseEntity.ok(output);
    }

    @GetMapping("/parameters")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateParameters(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listParameterIds(instId))
								.join(Utilities.getLineSeparator());
    	return ResponseEntity.ok(output);
    }

    @GetMapping("/operations")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateOperations(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listOperationIds(instId))
								.join(Utilities.getLineSeparator());
    	return ResponseEntity.ok(output);		
    }

    @GetMapping("/arguments")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateArguments(@RequestParam(name="instance") String instId,
    												@RequestParam(name="operation") String opId) {
		String output = FStream.from(m_instanceManager.listArgumentIds(instId, opId))
								.join(Utilities.getLineSeparator());
    	return ResponseEntity.ok(output);
    }

    @GetMapping("/submodels")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateSubmodels(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listSubmodelIds(instId))
								.join(Utilities.getLineSeparator());
    	return ResponseEntity.ok(output);
    }

    @GetMapping("/timeseries")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<?> listCandidateTimeSeries(@RequestParam(name="instance") String instId) {
		String output = FStream.from(m_instanceManager.listTimeSeriesIds(instId))
								.join(Utilities.getLineSeparator());
    	return ResponseEntity.ok(output);
    }

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
		
		SubmodelService svc = Try.get(() -> inst.getSubmodelServiceByIdShort(smIdShort))
									.getOrNull();
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
