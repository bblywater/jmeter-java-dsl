package us.abstracta.jmeter.javadsl.core.configs;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.protocol.java.sampler.JSR223Sampler;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import us.abstracta.jmeter.javadsl.codegeneration.MethodCall;
import us.abstracta.jmeter.javadsl.codegeneration.MethodCallContext;
import us.abstracta.jmeter.javadsl.codegeneration.MethodCallContext.MethodCallContextEndListener;
import us.abstracta.jmeter.javadsl.codegeneration.SingleTestElementCallBuilder;
import us.abstracta.jmeter.javadsl.codegeneration.params.StringParam;
import us.abstracta.jmeter.javadsl.core.BuildTreeContext;

/**
 * Allows setting JMeter thread variables that can be used later on in JMeter expressions or JSR223
 * scripts.
 * <p>
 * When you only need to reuse some string or value, prefer using java variables instead of creating
 * jmeter variables, which is easier to maintain, and makes the test plan more efficient (fewer
 * variables in memory).
 * <p>
 * This internally uses JMeter User Defined Variables element when variables are included at test
 * plan level, but uses JSR223 sampler otherwise to provide more consistency and avoid confusion
 * generated by User Defined Variables semantics (like non reinitialization in threads iterations,
 * vars cross-references, vars scope, etc. Check red blocks comments in
 * <a href="https://jmeter.apache.org/usermanual/component_reference.html#User_Defined_Variables">
 * JMeter component documentation</a>).
 *
 * @since 0.50
 */
public class DslVariables extends BaseConfigElement {

  private final Map<String, String> vars = new LinkedHashMap<>();

  public DslVariables() {
    super("User Defined Variables", ArgumentsPanel.class);
  }

  /**
   * Allows setting a JMeter thread variable.
   * <p>
   * When the variable is set at test plan level, one variable for each thread group is created at
   * test plan start with given name and value. When inside a thread group, controller, etc, it will
   * only be created/updated with given value when such point in test plan is reached.
   *
   * @param varName  specifies the name of the variable to register. This can be a literal value
   *                 (eg: "test") or a JMeter expression (eg: <pre>${VAR_NAME}</pre>).
   * @param varValue specifies the value associated to the variable. This can be a literal value
   *                 (eg: "test") or a JMeter expression (eg: <pre>${VAR_VALUE}</pre>).
   * @return the DslVariables instance for further configuration or usage in test plan.
   */
  public DslVariables set(String varName, String varValue) {
    vars.put(varName, varValue);
    return this;
  }

  @Override
  public HashTree buildTreeUnder(HashTree parent, BuildTreeContext context) {
    TestElement ret = context.getParent().isRoot() ? buildTestElement() : buildJs223Sampler();
    return parent.add(configureTestElement(ret, name, guiClass));
  }

  @Override
  protected TestElement buildTestElement() {
    Arguments ret = new Arguments();
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      ret.addArgument(entry.getKey(), entry.getValue());
    }
    return ret;
  }

  private JSR223Sampler buildJs223Sampler() {
    name = "Set Variables";
    guiClass = TestBeanGUI.class;
    JSR223Sampler ret = new JSR223Sampler();
    ret.setScriptLanguage("groovy");
    ret.setScript(buildGroovyScript());
    return ret;
  }

  private String buildGroovyScript() {
    boolean containsFunction = false;
    StringBuilder ret = new StringBuilder();
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      boolean keyContainsFunction = stringContainsFunction(entry.getKey());
      boolean valueContainsFunction = stringContainsFunction(entry.getValue());
      containsFunction = containsFunction || keyContainsFunction || valueContainsFunction;
      ret.append(String.format("vars.put(%s, %s)\n",
          formatStringParam(entry.getKey(), keyContainsFunction),
          formatStringParam(entry.getValue(), valueContainsFunction)));
    }
    ret.append("SampleResult.ignore = true\n");
    return (containsFunction ? "// need to use CompoundVariable with string replacement to avoid "
        + "generating incorrect groovy when expression resulting string contains groovy special "
        + "characters (like apostrophes, new lines, etc).\n"
        + "import org.apache.jmeter.engine.util.CompoundVariable\n\n" : "") + ret;
  }

  private boolean stringContainsFunction(String val) {
    return val.contains("${");
  }

  private String formatStringParam(String param, boolean containsFunction) {
    String escapedParam = escapeGroovyString(param);
    return containsFunction
        ? String.format("new CompoundVariable('%s'.replaceAll('([$#])#\\\\{', '$1{')).execute()",
        escapedParam.replaceAll("([$#])\\{", "$1#{"))
        : "'" + escapedParam + "'";
  }

  private String escapeGroovyString(String var) {
    return var
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  public static class CodeBuilder extends SingleTestElementCallBuilder<Arguments> {

    public CodeBuilder(List<Method> builderMethods) {
      super(Arguments.class, builderMethods);
    }

    @Override
    protected MethodCall buildMethodCall(Arguments testElement, MethodCallContext context) {
      mergeVariablesIntoRootContextVariables(testElement, context);
      return MethodCall.emptyCall();
    }

    private void mergeVariablesIntoRootContextVariables(Arguments testElement,
        MethodCallContext context) {
      MethodCallContext rootContext = context.getRoot();
      CallContextEntry entry = (CallContextEntry) rootContext.getEntry(getClass());
      if (entry == null) {
        entry = new CallContextEntry();
        rootContext.setEntry(getClass(), entry);
        rootContext.addEndListener(buildContextEndListener());
      }
      entry.vars.putAll(testElement.getArgumentsAsMap());
    }

    private MethodCallContextEndListener buildContextEndListener() {
      return (ctx, call) -> {
        CallContextEntry ctxEntry = (CallContextEntry) ctx.getEntry(getClass());
        if (ctxEntry.vars.isEmpty()) {
          return;
        }
        MethodCall ret = buildMethodCall();
        ctxEntry.vars.forEach((k, v) ->
            ret.chain("set", new StringParam(k), new StringParam(v))
        );
        call.child(ret);
      };
    }

  }

  public static class CallContextEntry {

    private final Map<String, String> vars = new LinkedHashMap<>();

  }

}
