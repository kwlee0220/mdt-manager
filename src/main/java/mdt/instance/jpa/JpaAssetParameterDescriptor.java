package mdt.instance.jpa;

import java.util.Collections;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import lombok.Getter;
import lombok.Setter;

import utils.stream.FStream;

import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.sm.SubmodelUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Entity
@Table(
	name="asset_parameter_descriptors"
)
@JsonIncludeProperties({"name", "valueType"})
public class JpaAssetParameterDescriptor implements MDTParameterDescriptor {
	private static final Logger s_logger = LoggerFactory.getLogger(JpaAssetParameterDescriptor.class);
	
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name="instance_row_id")
	private JpaInstanceDescriptor instance;
	
	@Column(name="name", length=255)
	private String name;
	
	@Column(name="valueType", length=255)
	private String valueType;
	
	@Override
	public String toString() {
		return String.format("%s:%s", this.name, this.valueType);
	}

	public static List<JpaAssetParameterDescriptor> loadJpaAssetParameterList(JpaInstanceDescriptor instDesc,
																				Submodel submodel) {
		if ( instDesc.getAssetType()== null ) {
			throw new IllegalArgumentException("instance assetType is null: instance=" + instDesc.getId());
		}
		
		switch ( instDesc.getAssetType().toUpperCase() ) {
			case "EQUIPMENT":
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading EQUIPMENT parameters: instance=" + instDesc.getId());
				}
				return loadEquipmentParameters(instDesc, submodel);
			case "PROCESS":
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading OPERATION parameters: instance=" + instDesc.getId());
				}
				return loadOperationParameters(instDesc, submodel);
			case "LINE":
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Line parameters: instance=" + instDesc.getId());
				}
				return Collections.emptyList();
			default:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Unknown Asset parameters: instance=" + instDesc.getId());
				}
				try {
					return loadEquipmentParameters(instDesc, submodel);
				}
				catch ( Exception e ) {
					return loadOperationParameters(instDesc, submodel);
				}
		}
	}
	
	private static List<JpaAssetParameterDescriptor> loadEquipmentParameters(JpaInstanceDescriptor instDesc,
																			Submodel submodel) {
		try {
			List<SubmodelElement> paramValues = SubmodelUtils.traverse(submodel,
																	"DataInfo.Equipment.EquipmentParameterValues",
																	SubmodelElementList.class).getValue();
			return FStream.from(paramValues)
						.map(param -> toAssetParameterDescriptor(instDesc, param))
						.toList();
		}
		catch ( ResourceNotFoundException e ) {
			return Collections.emptyList();
		}
	}
	
	private static List<JpaAssetParameterDescriptor> loadOperationParameters(JpaInstanceDescriptor instDesc,
																				Submodel submodel) {
		try {
			List<SubmodelElement> paramValues = SubmodelUtils.traverse(submodel,
																	"DataInfo.Operation.OperationParameterValues",
																	SubmodelElementList.class).getValue();
			return FStream.from(paramValues)
						.map(param -> toAssetParameterDescriptor(instDesc, param))
						.toList();
		}
		catch ( ResourceNotFoundException e ) {
			return Collections.emptyList();
		}
	}

	private static JpaAssetParameterDescriptor toAssetParameterDescriptor(JpaInstanceDescriptor instDesc,
																			SubmodelElement paramValue) {
		JpaAssetParameterDescriptor desc = new JpaAssetParameterDescriptor();
		
		String name = SubmodelUtils.traverse(paramValue, "ParameterID", Property.class).getValue();
		desc.setName(name);
		desc.setInstance(instDesc);
		
		SubmodelElement valueSme = SubmodelUtils.traverse(paramValue, "ParameterValue");
		desc.setValueType(SubmodelUtils.getTypeString(valueSme));
		
		return desc;
	}
}