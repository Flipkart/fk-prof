package fk.prof;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class PerfCtxUnitTest {
    private TestJni testJni;

    @Before
    public void setUp() {
        TestJni.loadJniLib();
        testJni = new TestJni();
        testJni.setupPerfCtx();
        testJni.setupThdTracker();
    }

    @After
    public void tearDown() {
        testJni.teardownThdTracker();
        testJni.teardownPerfCtx();
    }

    @Test
    public void shouldUnderstandEquality() {
        PerfCtx foo = new PerfCtx("foo", 10);
        PerfCtx bar = new PerfCtx("bar", 15);
        assertThat(foo, not(bar));
        PerfCtx baz = new PerfCtx("foo", 10);
        assertThat(foo, is(baz));
    }

    @Test
    public void shouldTrackCtx() {
        PerfCtx foo = new PerfCtx("foo", 15);
        long[] ctxs = new long[5];
        assertThat(testJni.getCurrentCtx(ctxs), is(0));
        foo.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 15, MergeSemantics.MERGE_TO_PARENT);
        foo.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(0));
    }

    private void assertUserCtxIs(long ctx, final String name, final int cov, final MergeSemantics expectedMSem) {
        _assertCtxIs(ctx, name, false);
        assertThat(testJni.getCtxCov(ctx), is(cov));
        int mSem = testJni.getCtxMergeSemantic(ctx);
        MergeSemantics semantic = null;
        for (MergeSemantics potential : MergeSemantics.values()) {
            if (potential.getTypeId() == mSem) {
                semantic = potential;
                break;
            }
        }
        assertThat(semantic, is(expectedMSem));
    }

    private void _assertCtxIs(long ctx, String name, boolean generated) {
        assertThat(testJni.getCtxName(ctx), is(name));
        assertThat(testJni.isGenerated(ctx), is(generated));
    }

    private void assertGeneratedCtxIs(long ctx, String name) {
        _assertCtxIs(ctx, name, true);
    }

    @Test
    public void shouldPassNameAndCoverage_whileRegistering() throws NoSuchFieldException, IllegalAccessException {
        PerfCtx foo = new PerfCtx("foo", 15, MergeSemantics.DUPLICATE);
        Field fieldCtxId = PerfCtx.class.getDeclaredField("ctxId");
        fieldCtxId.setAccessible(true);
        long ctxId = (long) fieldCtxId.get(foo);
        assertUserCtxIs(ctxId, "foo", 15, MergeSemantics.DUPLICATE);
    }

    @Test
    public void shouldNotAllowInvalidValuesForCoverage() {
        try {
            new PerfCtx("foo", 101);
            fail("PerfCtx creation should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("Coverage-percentage value 101 is not valid, a valid value must be in the range [0, 100]."));
        }

        try {
            new PerfCtx("foo", -1);
            fail("PerfCtx creation should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("Coverage-percentage value -1 is not valid, a valid value must be in the range [0, 100]."));
        }
    }

    @Test
    public void shouldNotAllow_RestrictedChars_InCtxName() {
        for (String s : new String[]{"~", "%", "<", ">"}) {
            try {
                new PerfCtx("foo" + s, 10);
                fail("PerfCtx creation should have failed");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage(), is(String.format("Name 'foo%s' has invalid character(s), chars '%%' and '~' are not allowed.", s)));
            }
        }

        for (String s : new String[]{",", ".", " ", "!", "@", "#", "$", "^", "&", "(", ")", "[", "]", "{", "}", "/", "'", "\"", ";", ":", "?"}) {
            try {
                new PerfCtx("foo" + s, 10);
            } catch (IllegalArgumentException e) {
                fail("PerfCtx creation should NOT have failed");
            }
        }
    }

    @Test
    public void shouldEnforceThatNameStartsWith_AnAlphaNumChar() {
        try {
            new PerfCtx("#foo", 10);
            fail("PerfCtx creation should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is(String.format("Name '#foo' has an invalid starting character, first-char must be alpha-numeric.")));
        }

        try {
            new PerfCtx(" ", 10);
            fail("PerfCtx creation should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is(String.format("Name ' ' has an invalid starting character, first-char must be alpha-numeric.")));
        }

        try {
            new PerfCtx("Foo", 10);
        } catch (IllegalArgumentException e) {
            fail("PerfCtx creation should NOT have failed");
        }

        try {
            new PerfCtx("10th Ctx", 10);
        } catch (IllegalArgumentException e) {
            fail("PerfCtx creation should NOT have failed");
        }
    }

    @Test
    public void shouldNotAllowContext_withNameSimilarTo_ImplicitlyAppliedContexts() {
        String defaultCtxName = testJni.getDefaultCtxName();
        String unknownCtxName = testJni.getUnknownCtxName();
        assertExcepts(defaultCtxName);
        assertExcepts(unknownCtxName);
        assertExcepts(defaultCtxName.substring(0, defaultCtxName.length() / 2));
        assertExcepts(unknownCtxName.substring(0, defaultCtxName.length() / 2));
        assertExcepts(defaultCtxName.substring(defaultCtxName.length() / 2));
        assertExcepts(unknownCtxName.substring(defaultCtxName.length() / 2));
    }

    public void assertExcepts(String name) {
        try {
            new PerfCtx(name);
            fail("Should not allow creation of perf-ctx with name: " + name);
        } catch (IllegalArgumentException e) {
            //ignore
        }
    }

    @Test
    public void shouldTrackContext_whenUsedIn_TryWithResources_Style() {
        //testJni.getAndStubCtxIdStart(105);
        PerfCtx ctx = new PerfCtx("foo bar baz", 25);
        long[] ctxs = new long[5];
        assertThat(testJni.getCurrentCtx(ctxs), is(0));
        try (ClosablePerfCtx x = ctx.open()) {
            assertThat(testJni.getCurrentCtx(ctxs), is(1));
            assertUserCtxIs(ctxs[0], "foo bar baz", 25, MergeSemantics.MERGE_TO_PARENT);
        }
        assertThat(testJni.getCurrentCtx(ctxs), is(0));
    }

    @Test
    public void shouldPrintUseful_ToString() {
        PerfCtx ctx = new PerfCtx("foo bar baz", 25);
        assertThat(ctx.toString(), is("PerfCtx(1801439850948198402) {name: 'foo bar baz', coverage: 25%, merge_semantics: 'MergeSemantics{typeId=0} MERGE_TO_PARENT'}"));
        ClosablePerfCtx open = ctx.open();
        assertThat(open.toString(), is("ClosablePerfCtx(1801439850948198402) {name: 'foo bar baz', coverage: 25%, merge_semantics: 'MergeSemantics{typeId=0} MERGE_TO_PARENT'}"));
    }

    @Test
    public void shouldUnderstandEqualityIn_ClosablePerfCtx_Interface() {
        PerfCtx ctx = new PerfCtx("foo bar baz", 25);
        ClosablePerfCtx open = ctx.open();
        PerfCtx ctx1 = new PerfCtx("foo bar baz", 25);
        ClosablePerfCtx open1 = ctx1.open();
        assertThat(open, is(open1));
        open.close();
        assertThat(open, is(open1));
        open1.close();
        assertThat(open, is(open1));
    }

    @Test
    public void shouldDefaultParams_sensibly() {
        PerfCtx foo = new PerfCtx("foo");
        assertThat(foo.toString(), is("PerfCtx(720575940379279362) {name: 'foo', coverage: 10%, merge_semantics: 'MergeSemantics{typeId=0} MERGE_TO_PARENT'}"));
    }

    @Test
    public void shouldFail_whenConflictingDefinitionsOfCtxAreProvided() {
        PerfCtx old = new PerfCtx("foo", 10, MergeSemantics.STACK_UP);
        for (MergeSemantics mergeSem : MergeSemantics.values()) {
            if (!mergeSem.equals(MergeSemantics.STACK_UP)) {
                try {
                    new PerfCtx("foo", 10, mergeSem);
                    fail("Conflicting definition of perf-ctx was permitted.");
                } catch (PerfCtxInitException e) {
                    assertThat(e.getMessage(), is("PerfCtx creation failed: New value (cov: 10%, merge: " + mergeSem.getTypeId() + ") for ctx 'foo' conflicts with old value (cov: 10%, merge: 3)"));
                }
            }
        }
        for (int i = 0; i < 100; i++) {
            if (i != 10) {
                try {
                    new PerfCtx("foo", i, MergeSemantics.STACK_UP);
                    fail("Conflicting definition of perf-ctx was permitted.");
                } catch (PerfCtxInitException e) {
                    assertThat(e.getMessage(), is("PerfCtx creation failed: New value (cov: " + i + "%, merge: 3) for ctx 'foo' conflicts with old value (cov: 10%, merge: 3)"));
                }
            }
        }
    }

    @Test
    public void shouldHandle_MergeToParent_Correctly() {
        PerfCtx p = new PerfCtx("foo", 10, MergeSemantics.DUPLICATE);//this can really be anything
        PerfCtx c = new PerfCtx("bar", 20, MergeSemantics.MERGE_TO_PARENT);

        long[] ctxs = new long[5];

        p.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.DUPLICATE);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.DUPLICATE);
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.DUPLICATE);
        p.end();
    }

    @Test
    public void shouldHandle_Scoping_Correctly() {
        PerfCtx p = new PerfCtx("foo", 10, MergeSemantics.MERGE_TO_PARENT);//this can really be anything
        PerfCtx c = new PerfCtx("bar", 20, MergeSemantics.PARENT_SCOPED);

        long[] ctxs = new long[5];

        p.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertGeneratedCtxIs(ctxs[0], "foo > bar");
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertGeneratedCtxIs(ctxs[0], "foo > bar");
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertGeneratedCtxIs(ctxs[0], "foo > bar");
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        p.end();
    }

    @Test
    public void shouldHandle_StrictScoping_Correctly() {
        PerfCtx p = new PerfCtx("foo", 10, MergeSemantics.MERGE_TO_PARENT);//this can really be anything
        PerfCtx c = new PerfCtx("bar", 20, MergeSemantics.PARENT_SCOPED_STRICT);

        long[] ctxs = new long[5];

        p.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertGeneratedCtxIs(ctxs[0], "foo > bar");
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertGeneratedCtxIs(ctxs[0], "foo > bar > bar");
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertGeneratedCtxIs(ctxs[0], "foo > bar");
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        p.end();
    }

    @Test
    public void shouldHandle_Stacking_Correctly() {
        PerfCtx p = new PerfCtx("foo", 10, MergeSemantics.MERGE_TO_PARENT);//this can really be anything
        PerfCtx c = new PerfCtx("bar", 20, MergeSemantics.STACK_UP);

        long[] ctxs = new long[5];

        p.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "bar", 20, MergeSemantics.STACK_UP);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "bar", 20, MergeSemantics.STACK_UP);
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "bar", 20, MergeSemantics.STACK_UP);
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        p.end();
    }

    @Test
    public void shouldHandle_Duplication_Correctly() {
        PerfCtx p = new PerfCtx("foo", 10, MergeSemantics.MERGE_TO_PARENT);//this can really be anything
        PerfCtx c = new PerfCtx("bar", 20, MergeSemantics.DUPLICATE);

        long[] ctxs = new long[5];

        p.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(2));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        assertUserCtxIs(ctxs[1], "bar", 20, MergeSemantics.DUPLICATE);
        c.begin();
        assertThat(testJni.getCurrentCtx(ctxs), is(2));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        assertUserCtxIs(ctxs[1], "bar", 20, MergeSemantics.DUPLICATE);
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(2));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        assertUserCtxIs(ctxs[1], "bar", 20, MergeSemantics.DUPLICATE);
        assertThat(testJni.getCurrentCtx(ctxs), is(2));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        assertUserCtxIs(ctxs[1], "bar", 20, MergeSemantics.DUPLICATE);
        c.end();
        assertThat(testJni.getCurrentCtx(ctxs), is(1));
        assertUserCtxIs(ctxs[0], "foo", 10, MergeSemantics.MERGE_TO_PARENT);
        p.end();
    }

    @Test
    public void shouldHandle_CtxEntryOverflow_Correctly() {
        List<PerfCtx> defs = new ArrayList<>();
        for (MergeSemantics mSem : MergeSemantics.values()) {
            for (int i = 0; i < 20; i++) {
                defs.add(new PerfCtx(String.format("%s - %s", mSem, i), i, mSem));
            }
        }

        Set<String> namesSeen = new HashSet<>();

        long[] ctxs = new long[5];

        for (int i = 0; i < 90; i++) {
            for (int j = 0; j < 10; j++) {
                defs.get(i + j).begin();
            }
            int len = testJni.getCurrentCtx(ctxs);
            namesSeen.add(testJni.getCtxName(ctxs[len - 1]));
            for (int j = 9; j >= 0; j--) {
                defs.get(i + j).end();
            }
        }

        assertThat(namesSeen.size(), is(90));
    }

    @Test
    public void shouldFail_WhenCtxCreationLimit_IsExceeded() {
        List<PerfCtx> defs = new ArrayList<>();
        int i;
        int limit = 224;
        for (i = 0; i < limit / 5; i++) {
            for (MergeSemantics mSem : MergeSemantics.values()) {
                defs.add(new PerfCtx(String.format("%s - %s", mSem, i), i, mSem));
            }
        }
        for (int j = 0; j < (limit % 5); j++) {
            MergeSemantics mSem = MergeSemantics.DUPLICATE;
            defs.add(new PerfCtx(String.format("%s - %s", mSem, i + j), i + j, mSem));
        }

        assertThat(defs.size(), is(224));

        try {
            new PerfCtx(String.valueOf(i), i, MergeSemantics.STACK_UP);
            fail("Should not have allowed creation of " + (limit + 1) + "th ctx");
        } catch (PerfCtxInitException e) {
            assertThat(e.getMessage(), is("PerfCtx creation failed: Too many (~ 224) ctxs have been created."));
        }
    }

    @Test
    public void shouldFail_IncorrectPairingOf_ContextEnterExitCalls() {
        PerfCtx foo = new PerfCtx("foo");
        PerfCtx bar = new PerfCtx("bar", 10, MergeSemantics.STACK_UP);
        long[] ctxIds = new long[5];

        assertThat(testJni.getCurrentCtx(ctxIds), is(0));
        try {
            bar.end();
        } catch (IncorrectContextException e) {
            assertThat(e.getMessage(), is("Unexpected exit for 'bar'(747597538143502339)"));
        }
        assertThat(testJni.getCurrentCtx(ctxIds), is(0));

        foo.begin();
        assertThat(testJni.getCurrentCtx(ctxIds), is(1));
        assertThat(testJni.getCtxName(ctxIds[0]), is("foo"));
        try {
            bar.end();
        } catch (IncorrectContextException e) {
            assertThat(e.getMessage(), is("Expected exit for 'foo'(720575940379279362) got 'bar'(747597538143502339)"));
        }
        assertThat(testJni.getCurrentCtx(ctxIds), is(1));
        assertThat(testJni.getCtxName(ctxIds[0]), is("foo"));

        bar.begin();
        assertThat(testJni.getCurrentCtx(ctxIds), is(1));
        assertThat(testJni.getCtxName(ctxIds[0]), is("bar"));
        try {
            foo.end();
        } catch (IncorrectContextException e) {
            assertThat(e.getMessage(), is("Expected exit for 'bar'(747597538143502339) got 'foo'(720575940379279362)"));
        }
        assertThat(testJni.getCurrentCtx(ctxIds), is(1));
        assertThat(testJni.getCtxName(ctxIds[0]), is("bar"));
        bar.end();
        assertThat(testJni.getCurrentCtx(ctxIds), is(1));
        assertThat(testJni.getCtxName(ctxIds[0]), is("foo"));
        try {
            bar.end();
        } catch (IncorrectContextException e) {
            assertThat(e.getMessage(), is("Expected exit for 'foo'(720575940379279362) got 'bar'(747597538143502339)"));
        }
        assertThat(testJni.getCurrentCtx(ctxIds), is(1));
        assertThat(testJni.getCtxName(ctxIds[0]), is("foo"));
        foo.end();
        assertThat(testJni.getCurrentCtx(ctxIds), is(0));
        try {
            foo.end();
        } catch (IncorrectContextException e) {
            assertThat(e.getMessage(), is("Unexpected exit for 'foo'(720575940379279362)"));
        }
        assertThat(testJni.getCurrentCtx(ctxIds), is(0));
    }
}
