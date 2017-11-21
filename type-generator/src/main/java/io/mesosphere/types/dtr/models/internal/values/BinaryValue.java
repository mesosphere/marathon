package io.mesosphere.types.dtr.models.internal.values;

import io.mesosphere.types.dtr.models.internal.Value;
import java.util.List;

/**
 * Binary value contains arbitrary binary data, such a files and other contents
 */
public class BinaryValue extends Value {

    /**
     * The minimum length of the blob
     */
    public Integer minLength;

    /**
     * The minimum length of the blob
     */
    public Integer maxLength;

    /**
     * The content types that are supported by this binary value
     */
    public List<String> contentTypes;

    /**
     * Constructor of binary value with full description
     * @param minLength The minimum content length
     * @param maxLength The maximum content length
     * @param contentTypes A list of one or more supported content types
     */
    public BinaryValue(Integer minLength, Integer maxLength, List<String> contentTypes) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.contentTypes = contentTypes;
    }

    @Override
    public String toName() { return "binary"; }

    @Override
    public Boolean equals(Value o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BinaryValue that = (BinaryValue) o;

        if (minLength != null ? !minLength.equals(that.minLength) : that.minLength != null) return false;
        if (maxLength != null ? !maxLength.equals(that.maxLength) : that.maxLength != null) return false;
        return contentTypes != null ? contentTypes.equals(that.contentTypes) : that.contentTypes == null;
    }
}
