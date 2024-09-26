package mdt;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;

import mdt.model.MDTExceptionEntity;
import mdt.model.MessageTypeEnum;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
public class MDTController<T> {
	protected static final JsonSerializer s_ser = new JsonSerializer();
	protected static final JsonDeserializer s_deser = new JsonDeserializer();
	
	protected String getBadArgumentResult(String details) {
		ZonedDateTime zdt = Instant.now().atZone(ZoneOffset.systemDefault());
		String tsStr = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(zdt);
		
		MDTExceptionEntity msg = new MDTExceptionEntity();
		msg.setMessageType(MessageTypeEnum.Error);
		msg.setText(details);
		msg.setTimestamp(tsStr);
		try {
			return s_ser.write(msg);
		}
		catch ( SerializationException e1 ) {
			return details;
		}
	}
}
