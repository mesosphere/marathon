package io.mesosphere.types.dtr.models.internal.values;

import io.mesosphere.types.dtr.models.internal.Value;

public class DateTimeValue extends Value.Dynamic {

    /**
     * True if this expression contains an interesting date part
     */
    public Boolean datePart;

    /**
     * True if this expression contains an interesting time part
     */
    public Boolean timePart;

    /**
     * True if this expression supports timezone information
     */
    public Boolean timezone;

    /**
     * Customising constructor
     * @param datePart True if the value includes a date part
     * @param timePart True if the value includes a time part
     * @param timezone True if the value supports time-zone
     */
    public DateTimeValue(Boolean datePart, Boolean timePart, Boolean timezone) {
        this.datePart = datePart;
        this.timePart = timePart;
        this.timezone = timezone;
    }

    @Override
    public Integer getMinimumSize() {
        return 0;
    }

    /**
     * Return the name of the storage value
     * @return Returns "date", "time", or "datetime"
     */
    @Override
    public String toName() {
        String suffix = "";
        if (!timezone) suffix = "_only";
        if (timePart) {
            if (datePart) return "datetime" + suffix;
            return "time" + suffix;
        }
        return "date" + suffix;
    }

    @Override
    public Boolean equals(Value o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DateTimeValue that = (DateTimeValue) o;

        if (datePart != null ? !datePart.equals(that.datePart) : that.datePart != null) return false;
        if (timePart != null ? !timePart.equals(that.timePart) : that.timePart != null) return false;
        return timezone != null ? timezone.equals(that.timezone) : that.timezone == null;
    }
}
