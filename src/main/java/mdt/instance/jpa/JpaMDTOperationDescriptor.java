package mdt.instance.jpa;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import utils.CSV;
import utils.stream.FStream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.NamedValueType;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Entity
@Table(
	name="asset_operation_descriptors"
)
@JsonIncludeProperties({"name", "operationType", "inputArguments", "outputArguments"})
public class JpaMDTOperationDescriptor implements MDTOperationDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name="instance_row_id")
	private JpaInstanceDescriptor instance;
	
	@Column(name="name", length=255)
	private String name;
	
	@Column(name="operationType", length=255)
	private String operationType;

	@Column(name="inputArgumentsString", length=1023)
	private String inputArgumentsString;

	@Column(name="outputArgumentsString", length=1023)
	private String outputArgumentsString;
	
	@Override
	public List<NamedValueType> getInputArguments() {
		return CSV.parseCsv(inputArgumentsString, ',')
					.map(NamedValueType::parseString)
					.toList();
	}
	
	@Override
	public List<NamedValueType> getOutputArguments() {
		return CSV.parseCsv(outputArgumentsString, ',')
					.map(pair -> NamedValueType.parseString(pair.trim()))
					.toList();
	}
	
	@Override
	public String toString() {
		String inArgsStr = FStream.from(getInputArguments())
									.map(NamedValueType::getName)
									.join(", ");
		String outArgsStr = FStream.from(getOutputArguments())
									.map(NamedValueType::getName)
									.join(", ");
		return String.format("%s(%s) -> %s", this.name, inArgsStr, outArgsStr);
	}
	
	static JpaMDTOperationDescriptor loadSimulationDescriptor(JpaInstanceDescriptor instDesc, Submodel submodel) {
		JpaMDTOperationDescriptor opDesc = new JpaMDTOperationDescriptor();
		opDesc.setInstance(instDesc);
		opDesc.setOperationType("Simulation");
		opDesc.setName(submodel.getIdShort());
		
		List<SubmodelElement> inputs = SubmodelUtils.traverse(submodel, "SimulationInfo.Inputs",
																SubmodelElementList.class).getValue();
		opDesc.setInputArgumentsString(toArgumentsJson(inputs, "Input"));
		
		List<SubmodelElement> outputs = SubmodelUtils.traverse(submodel, "SimulationInfo.Outputs",
																SubmodelElementList.class).getValue();
		opDesc.setOutputArgumentsString(toArgumentsJson(outputs, "Output"));
		
		return opDesc;
	}
	
	static JpaMDTOperationDescriptor loadAIDescriptor(JpaInstanceDescriptor instDesc, Submodel submodel) {
		JpaMDTOperationDescriptor opDesc = new JpaMDTOperationDescriptor();
		opDesc.setInstance(instDesc);
		opDesc.setOperationType("AI");
		opDesc.setName(submodel.getIdShort());
		
		List<SubmodelElement> inputs = SubmodelUtils.traverse(submodel, "AIInfo.Inputs",
																SubmodelElementList.class).getValue();
		opDesc.setInputArgumentsString(toArgumentsJson(inputs, "Input"));
		
		List<SubmodelElement> outputs = SubmodelUtils.traverse(submodel, "AIInfo.Outputs",
																SubmodelElementList.class).getValue();
		opDesc.setOutputArgumentsString(toArgumentsJson(outputs, "Output"));
		
		return opDesc;
	}
	
	private static NamedValueType toNamedElementType(SubmodelElementCollection arg, String prefix) {
		String argName = SubmodelUtils.traverse(arg, prefix + "ID", Property.class)
										.getValue();
		SubmodelElement argValue = SubmodelUtils.traverse(arg, prefix + "Value");
		String argType = SubmodelUtils.getTypeString(argValue);
		return new NamedValueType(argName, argType);
	}
	
	private static String toArgumentsJson(List<SubmodelElement> args, String prefix) {
		return FStream.from(args)
						.castSafely(SubmodelElementCollection.class)
						.map(arg -> toNamedElementType(arg, prefix))
						.join(", ");
	}
}