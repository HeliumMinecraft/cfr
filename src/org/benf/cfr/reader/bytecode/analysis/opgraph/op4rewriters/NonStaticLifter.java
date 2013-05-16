package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.util.MiscStatementTools;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.LValueExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.MemberFunctionInvokation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.SuperFunctionInvokation;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.FieldVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.StackSSALabel;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.StaticVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueAssignmentCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueUsageCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.parse.wildcard.WildcardMatch;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredAssignment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredComment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredExpressionStatement;
import org.benf.cfr.reader.bytecode.analysis.types.MethodPrototype;
import org.benf.cfr.reader.entities.*;
import org.benf.cfr.reader.util.*;
import org.benf.cfr.reader.util.functors.UnaryFunction;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: lee
 * Date: 14/05/2013
 * Time: 17:20
 * <p/>
 * Similar to the static lifter, however this has to cope with the possibility that EVERY constructor
 * will have had the non static initialisation pushed into it.
 */
public class NonStaticLifter {

    private final ClassFile classFile;
    private final ConstantPool cp;

    public NonStaticLifter(ClassFile classFile) {
        this.classFile = classFile;
        this.cp = classFile.getConstantPool();
    }

    private boolean isDelegating(Method constructor) {
        List<Op04StructuredStatement> statements = MiscStatementTools.getBlockStatements(constructor.getAnalysis());
        if (statements == null) return false;
        for (Op04StructuredStatement statement : statements) {
            StructuredStatement structuredStatement = statement.getStatement();
            if (structuredStatement instanceof StructuredComment) continue;
            if (!(structuredStatement instanceof StructuredExpressionStatement)) return false;
            StructuredExpressionStatement structuredExpressionStatement = (StructuredExpressionStatement) structuredStatement;

            WildcardMatch wcm1 = new WildcardMatch();
            StructuredStatement test = new StructuredExpressionStatement(wcm1.getMemberFunction("m", null /* this method */, new LValueExpression(wcm1.getLValueWildCard("o")), (List<Expression>) null), false);
            if (test.equals(structuredExpressionStatement)) {
                MemberFunctionInvokation m = wcm1.getMemberFunction("m").getMatch();
                MethodPrototype prototype = m.getMethodPrototype();
                return true;
            }
            return false;
        }
        return false;
    }

    public void liftNonStatics() {

        // All uninitialised static fields, in definition order.
        LinkedList<ClassFileField> classFileFields = new LinkedList<ClassFileField>(Functional.filter(classFile.getFields(), new Predicate<ClassFileField>() {
            @Override
            public boolean test(ClassFileField in) {
                if (in.getField().testAccessFlag(AccessFlag.ACC_STATIC)) return false;
                if (in.getField().testAccessFlag(AccessFlag.ACC_SYNTHETIC)) return false;
                // Members may well have an initial value. If they do, we need to make sure that it is
                // exactly the same as the one we're lifting, or we abort.
                return true;
            }
        }));
        if (classFileFields.isEmpty()) return;
        Map<String, Pair<Integer, ClassFileField>> fieldMap = MapFactory.newMap();
        for (int x = 0, len = classFileFields.size(); x < len; ++x) {
            ClassFileField classFileField = classFileFields.get(x);
            fieldMap.put(classFileField.getField().getFieldName(cp), Pair.make(x, classFileField));
        }

        List<Method> constructors = Functional.filter(classFile.getConstructors(), new Predicate<Method>() {
            @Override
            public boolean test(Method in) {
                return !isDelegating(in);
            }
        });

        /* These constructors are ones which do not delegate, i.e. we would expect them to share common initialisation
         * code.  (If they don't it's not the end of the world, we're tidying up).
         */

        List<List<Op04StructuredStatement>> constructorCodeList = ListFactory.newList();
        int minSize = Integer.MAX_VALUE;
        for (Method constructor : constructors) {
            List<Op04StructuredStatement> blockStatements = MiscStatementTools.getBlockStatements(constructor.getAnalysis());
            if (blockStatements == null) return;
            blockStatements = Functional.filter(blockStatements, new Predicate<Op04StructuredStatement>() {
                @Override
                public boolean test(Op04StructuredStatement in) {
                    return (!(in.getStatement() instanceof StructuredComment));
                }
            });
            if (blockStatements.isEmpty()) return;

            /*
             * If the first statement is a super init, we trim that.
             * Bit inefficient.
             */
            StructuredStatement superTest = blockStatements.get(0).getStatement();
            if (superTest instanceof StructuredExpressionStatement) {
                Expression expression = ((StructuredExpressionStatement) superTest).getExpression();
                if (expression instanceof SuperFunctionInvokation) blockStatements.remove(0);
            }

            constructorCodeList.add(blockStatements);
            if (blockStatements.size() < minSize) minSize = blockStatements.size();
        }

        /*
         * We have to be more involved than in a static constructor - each of the statements has to match.
         */
        int numConstructors = constructorCodeList.size();
        for (int x = 0; x < minSize; ++x) {
            StructuredStatement s1 = constructorCodeList.get(0).get(x).getStatement();
            for (int y = 1; y < numConstructors; ++y) {
                StructuredStatement sOther = constructorCodeList.get(y).get(x).getStatement();
                if (!s1.equals(sOther)) return;
            }
            /*
             * Ok, they're all the same.  Now, is this an assignment to a member, AND does it use only other fields,
             * which have already been initialised? (and are not forward references) Sheeeesh....
             */
            if (!(s1 instanceof StructuredAssignment)) return;
            StructuredAssignment structuredAssignment = (StructuredAssignment) s1;
            LValue lValue = structuredAssignment.getLvalue();
            if (!(lValue instanceof FieldVariable)) return;
            if (!fromThisClass((FieldVariable) lValue)) return;

            /*
             * Ok, every field before this (which has been initialised) is usable.  But nothing else....
             */
            if (!tryLift((FieldVariable) lValue, structuredAssignment.getRvalue(), fieldMap)) {
                return;
            }
            for (List<Op04StructuredStatement> constructorCode : constructorCodeList) {
                constructorCode.get(x).nopOut();
            }
        }


    }

    private boolean fromThisClass(FieldVariable fv) {
        return fv.getOwningClassType().equals(classFile.getClassType());
    }

    private boolean tryLift(FieldVariable lValue, Expression rValue, Map<String, Pair<Integer, ClassFileField>> fieldMap) {
        Pair<Integer, ClassFileField> thisField = fieldMap.get(lValue.getFieldName());
        if (thisField == null) return false;
        ClassFileField classFileField = thisField.getSecond();
        int thisIdx = thisField.getFirst();
        LValueUsageCollector usageCollector = new LValueUsageCollector();
        rValue.collectUsedLValues(usageCollector);
        for (LValue usedLValue : usageCollector.getUsedLValues()) {
            if (usedLValue instanceof StaticVariable) {
                // that's ok, these must have been initialised first.
                continue;
            }
            if (usedLValue instanceof FieldVariable) {
                // Is it a) defined before b) already has value?
                FieldVariable usedFieldVariable = (FieldVariable) usedLValue;
                if (!fromThisClass(usedFieldVariable)) return false;
                Pair<Integer, ClassFileField> usedField = fieldMap.get(usedFieldVariable.getFieldName());
                if (usedField == null) return false;
                ClassFileField usedClassFileField = usedField.getSecond();
                int usedIdx = usedField.getFirst();
                if (usedIdx >= thisIdx) return false;
                if (usedClassFileField.getInitialValue() == null) return false;
                continue;
            }
            // Other lvalue - can't allow.
            return false;
        }
        // Ok, it doesn't use anything it shouldn't - change the initialiser.
        classFileField.setInitialValue(rValue);
        return true;
    }


}