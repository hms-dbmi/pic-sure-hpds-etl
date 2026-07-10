package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

/**
 * Declares one runtime parameter a job accepts (passed as {@code --name=value}).
 * The set of ParamSpecs is a job's <em>input expectation</em>: it both documents the
 * job and drives automatic presence-checking of required params in AbstractJob.
 *
 * @param name        parameter key, e.g. "input"
 * @param required    if true, AbstractJob fails input validation when it is absent
 * @param description what it is / how it's used
 * @param example     a concrete example value, shown in --help output
 */
public record ParamSpec(String name, boolean required, String description, String example) {

    public static ParamSpec required(String name, String description, String example) {
        return new ParamSpec(name, true, description, example);
    }

    public static ParamSpec optional(String name, String description, String example) {
        return new ParamSpec(name, false, description, example);
    }
}
