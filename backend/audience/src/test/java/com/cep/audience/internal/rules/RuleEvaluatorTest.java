package com.cep.audience.internal.rules;

import static com.cep.audience.internal.rules.RuleCompilerTest.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.cep.audience.internal.rules.RuleEvaluator.Leaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuleEvaluatorTest {
    final RuleCompiler compiler = RuleCompiler.forAudiences();

    static final String CUSTOMER = """
            {'customer':{'email':'a@b.sg','attributes':{
               'status':'ACTIVE','tier':'GOLD','age':30,'score':7.5,'vip':true,'city':'Singapore',
               'tags':['roaming','5g'],'joined':'2024-03-01','lastSeen':'2026-11-01T08:00:00Z','note':null}},
             'event':{'payload':{'amount':2500}}}""";

    boolean matches(String content) {
        return RuleEvaluator.evaluate(compiler.compile(json(content)).rule(), json(CUSTOMER)).member();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "{'status':'ACTIVE'}                                  | true",
            "{'status':'active'}                                  | false",
            "{'age':30.0}                                         | true",
            "{'age':'30'}                                         | false",
            "{'vip':true}                                         | true",
            "{'status':{'neq':'INACTIVE'}}                        | true",
            "{'missing':{'neq':'X'}}                              | false",
            "{'age':{'gt':29,'lte':30}}                           | true",
            "{'age':{'lt':30}}                                    | false",
            "{'score':{'between':[7,8]}}                          | true",
            "{'age':{'between':[31,40]}}                          | false",
            "{'tier':['GOLD','SILVER']}                           | true",
            "{'tier':{'nin':['GOLD']}}                            | false",
            "{'missing':{'nin':['GOLD']}}                         | false",
            "{'tags':{'contains':'5g'}}                           | true",
            "{'city':{'contains':'gap'}}                          | true",
            "{'email':{'exists':true}}                            | false",
            "{'customer.email':{'regex':'@b\\\\.sg$'}}            | true",
            "{'missing':{'exists':false}}                         | true",
            "{'note':{'exists':false}}                            | true",
            "{'joined':{'gte':'2024-01-01'}}                      | true",
            "{'lastSeen':{'between':['2026-10-01','2026-11-30']}} | true",
            "{'lastSeen':{'gt':'2026-11-01T09:00:00+02:00'}}      | true",
            "{'status':{'gt':5}}                                  | false",
            "{'event.payload.amount':{'gte':1000}}                | true",
            "{'customer.attributes.status.nested':'x'}            | false",
    })
    void operators(String content, boolean expected) {
        assertThat(matches(content)).isEqualTo(expected);
    }

    @Test
    void groupsAndNegation() {
        assertThat(matches("{'any':[{'tier':'SILVER'},{'all':[{'vip':true},{'age':{'gte':18}}]}]}")).isTrue();
        assertThat(matches("{'not':{'city':'Singapore'}}")).isFalse();
        assertThat(matches("{'all':[{'tier':'GOLD'},{'not':{'tags':{'contains':'roaming'}}}]}")).isFalse();
    }

    @Test
    void reportsMatchedAndFailedConditionsWithPaths() {
        var outcome = RuleEvaluator.evaluate(
                compiler.compile(json("{'all':[{'tier':'GOLD'},{'any':[{'age':{'lt':18}},{'vip':true}]}]}")).rule(),
                json(CUSTOMER));
        assertThat(outcome.member()).isTrue();
        assertThat(outcome.matched()).extracting(Leaf::path).containsExactly("$.all[0]", "$.all[1].any[1]");
        assertThat(outcome.failed()).extracting(Leaf::path, Leaf::field, Leaf::op)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("$.all[1].any[0]", "customer.attributes.age", "lt"));
    }

    @Test
    void catastrophicRegexIsCutOffAndDoesNotMatch() {
        String evil = "a".repeat(5000) + "!";
        var rule = compiler.compile(json("{'name':{'regex':'^(a+)+$'}}")).rule();
        long start = System.nanoTime();
        var outcome = RuleEvaluator.evaluate(rule, json("{'customer':{'attributes':{'name':'" + evil + "'}}}"));
        assertThat(outcome.member()).isFalse();
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    @Test
    void emptyContextFailsEveryConditionExceptExistsFalse() {
        var rule = compiler.compile(json("{'all':[{'a':{'exists':false}},{'b':{'neq':1}}]}")).rule();
        var outcome = RuleEvaluator.evaluate(rule, json("{}"));
        assertThat(outcome.member()).isFalse();
        assertThat(outcome.matched()).hasSize(1);
    }
}
