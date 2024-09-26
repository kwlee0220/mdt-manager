package mdt.exector.jar;

import mdt.model.instance.MDTInstanceManagerException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTInstanceExecutorException extends MDTInstanceManagerException {
    private static final long serialVersionUID = 1L;


    public MDTInstanceExecutorException(final String message) {
        super(message);
    }
}
