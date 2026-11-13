package com.cep.audience.internal.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cep.audience.internal.rules.RuleCompiler.InvalidRuleException;
import com.cep.audience.internal.rules.RuleCompiler.Issue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RuleCompilerTest {
    static final ObjectMapper JSON = JsonMapper.builder().build();
    final RuleCompiler compiler = RuleCompiler.forAudiences();

    static JsonNode json(String s) {
        return JSON.readTree(s.replace('\'', '"'));
    }

    List<Issue> issues(String content) {
        try {
            compiler.compile(json(content));
        } catch (InvalidRuleException e) {
            return e.issues();
        }
        throw new AssertionError("expected invalid: " + content);
    }

    @Test
    void shorthandCompilesToAllOfConditionsOnCustomerAttributes() {
        var c = compiler.compile(json("{'customerStatus':'ACTIVE','tier':['GOLD','SILVER'],'age':{'gte':18,'lt':65}}"));
        assertThat(c.normalized()).isEqualTo(json("""
                {'all':[
                  {'field':'customer.attributes.customerStatus','op':'eq','value':'ACTIVE'},
                  {'field':'customer.attributes.tier','op':'in','value':['GOLD','SILVER']},
                  {'field':'customer.attributes.age','op':'gte','value':18},
                  {'field':'customer.attributes.age','op':'lt','value':65}]}"""));
    }

    @Test
    void singleShorthandKeyIsWrappedInRootGroup() {
        assertThat(compiler.compile(json("{'customerStatus':'ACTIVE'}")).normalized())
                .isEqualTo(json("{'all':[{'field':'customer.attributes.customerStatus','op':'eq','value':'ACTIVE'}]}"));
    }

    @Test
    void shorthandKeyWithDotsIsAFullPath() {
        assertThat(compiler.compile(json("{'event.payload.amount':{'gte':1000}}")).normalized())
                .isEqualTo(json("{'all':[{'field':'event.payload.amount','op':'gte','value':1000}]}"));
    }

    @Test
    void fullTreeFromTheDesignDocIsAcceptedUnchanged() {
        String tree = """
                {'all':[{'field':'customer.attributes.membershipType','op':'eq','value':'GOLD'},
                        {'any':[{'field':'event.payload.amount','op':'gte','value':1000},
                                {'field':'customer.attributes.city','op':'in','value':['Singapore']}]}]}""";
        assertThat(compiler.compile(json(tree)).normalized()).isEqualTo(json(tree));
    }

    @Test
    void normalizedFormRecompilesToItself() {
        JsonNode once = compiler.compile(json("{'any':[{'a':1},{'not':{'b':{'exists':true}}}]}")).normalized();
        assertThat(compiler.compile(once).normalized()).isEqualTo(once);
    }

    @Test
    void bareConditionIsWrappedAndExistsDefaultsToTrue() {
        assertThat(compiler.compile(json("{'field':'customer.email','op':'exists'}")).normalized())
                .isEqualTo(json("{'all':[{'field':'customer.email','op':'exists','value':true}]}"));
    }

    @Test
    void reportsEveryProblemWithItsPath() {
        assertThat(issues("{'all':[{'field':'customer.x','op':'like','value':1},{'field':'order.total','op':'eq','value':1},{'any':[]}]}"))
                .extracting(Issue::path)
                .containsExactly("$.all[0].op", "$.all[1].field", "$.all[2].any");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "{}                                                            | $",
            "{'x':null}                                                    | $.x",
            "{'bad-name':1}                                                | $.bad-name",
            "{'x':{}}                                                      | $.x",
            "{'x':{'gte':'abc'}}                                           | $.x.gte",
            "{'x':{'gt':'A'}}                                              | $.x.gt",
            "{'x':{'in':[]}}                                               | $.x.in",
            "{'x':[{'a':1}]}                                               | $.x",
            "{'x':{'regex':'(unclosed'}}                                   | $.x.regex",
            "{'x':{'between':[10,1]}}                                      | $.x.between",
            "{'x':{'between':[1,'2026-01-01']}}                            | $.x.between",
            "{'x':{'exists':'yes'}}                                        | $.x.exists",
            "{'x':{'eq':{'nested':1}}}                                     | $.x.eq",
            "{'all':[{'a':1}],'b':2}                                       | $",
            "{'field':'customer.a','op':'eq','value':1,'extra':true}       | $.extra",
            "{'field':'customer','op':'eq','value':1}                      | $.field",
            "{'not':[]}                                                    | $.not",
    })
    void rejectsInvalidContent(String content, String path) {
        assertThat(issues(content)).extracting(Issue::path).contains(path);
    }

    @Test
    void limitsDepthAndConditionCount() {
        String deep = "{'a':1}";
        for (int i = 0; i < RuleCompiler.MAX_DEPTH; i++) {
            deep = "{'not':" + deep + "}";
        }
        String finalDeep = deep;
        assertThat(issues(finalDeep)).anyMatch(i -> i.message().contains("deeper"));

        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i <= RuleCompiler.MAX_CONDITIONS; i++) {
            many.append(i == 0 ? "" : ",").append("'a").append(i).append("':1");
        }
        assertThat(issues(many.append("}").toString())).anyMatch(i -> i.message().contains("conditions"));
    }

    @Test
    void rejectsNonObjectRoot() {
        assertThatThrownBy(() -> compiler.compile(json("[1]"))).isInstanceOf(InvalidRuleException.class);
    }
}
