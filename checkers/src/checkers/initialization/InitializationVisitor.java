package checkers.initialization;

/*>>>
import checkers.compilermsgs.quals.CompilerMessageKey;
import checkers.nullness.quals.Nullable;
*/

import checkers.basetype.BaseTypeVisitor;
import checkers.flow.CFAbstractStore;
import checkers.flow.CFAbstractValue;
import checkers.nullness.NullnessChecker;
import checkers.source.Result;
import checkers.types.AnnotatedTypeMirror;
import checkers.types.AnnotatedTypeMirror.AnnotatedDeclaredType;
import checkers.types.AnnotatedTypeMirror.AnnotatedExecutableType;

import dataflow.analysis.FlowExpressions.ClassName;
import dataflow.analysis.FlowExpressions.FieldAccess;
import dataflow.analysis.FlowExpressions.Receiver;
import dataflow.analysis.FlowExpressions.ThisReference;

import javacutils.AnnotationUtils;
import javacutils.ElementUtils;
import javacutils.Pair;
import javacutils.TreeUtils;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;

/**
 * The visitor for the freedom-before-commitment type-system. The
 * freedom-before-commitment type-system and this class are abstract and need to
 * be combined with another type-system whose safe initialization should be
 * tracked. For an example, see the {@link NullnessChecker}. Also supports
 * rawness as a type-system for tracking initialization, though FBC is
 * preferred.
 *
 * @author Stefan Heule
 */
public class InitializationVisitor<Checker extends InitializationChecker<? extends Factory>,
        Factory extends InitializationAnnotatedTypeFactory<?, Value, Store, ?, ?>,
        Value extends CFAbstractValue<Value>,
        Store extends InitializationStore<Value, Store>>
    extends BaseTypeVisitor<Checker, Factory> {

    // Error message keys
    private static final /*@CompilerMessageKey*/ String COMMITMENT_INVALID_CAST = "initialization.invalid.cast";
    private static final /*@CompilerMessageKey*/ String COMMITMENT_FIELDS_UNINITIALIZED = "initialization.fields.uninitialized";
    private static final /*@CompilerMessageKey*/ String COMMITMENT_INVALID_FIELD_ANNOTATION = "initialization.invalid.field.annotation";
    private static final /*@CompilerMessageKey*/ String COMMITMENT_INVALID_CONSTRUCTOR_RETURN_TYPE = "initialization.invalid.constructor.return.type";
    private static final /*@CompilerMessageKey*/ String COMMITMENT_INVALID_FIELD_WRITE_UNCLASSIFIED = "initialization.invalid.field.write.unknown";
    private static final /*@CompilerMessageKey*/ String COMMITMENT_INVALID_FIELD_WRITE_COMMITTED = "initialization.invalid.field.write.initialized";

    public InitializationVisitor(Checker checker, CompilationUnitTree root) {
        super(checker, root);
        checkForAnnotatedJdk();
    }

    @Override
    protected boolean checkConstructorInvocation(AnnotatedDeclaredType dt,
            AnnotatedExecutableType constructor, Tree src) {
        // receiver annotations for constructors are forbidden, therefore no
        // check is necessary
        return true;
    }

    @Override
    protected void commonAssignmentCheck(Tree varTree, ExpressionTree valueExp,
            /*@CompilerMessageKey*/ String errorKey) {
        // field write of the form x.f = y
        if (TreeUtils.isFieldAccess(varTree)) {
            // cast is safe: a field access can only be an IdentifierTree or
            // MemberSelectTree
            ExpressionTree lhs = (ExpressionTree) varTree;
            ExpressionTree y = valueExp;
            Element el = TreeUtils.elementFromUse(lhs);
            AnnotatedTypeMirror xType = atypeFactory.getReceiverType(lhs);
            AnnotatedTypeMirror yType = atypeFactory.getAnnotatedType(y);
            // the special FBC rules do not apply if there is an explicit
            // UnknownInitialization annotation
            Set<AnnotationMirror> fieldAnnotations =
                    atypeFactory.getAnnotatedType(TreeUtils.elementFromUse(lhs)).getAnnotations();
            if (!AnnotationUtils.containsSameIgnoringValues(
                    fieldAnnotations, checker.UNCLASSIFIED)) {
                if (!ElementUtils.isStatic(el)
                        && !(checker.isCommitted(yType) || checker.isFree(xType) || checker.isFbcBottom(yType))) {
                    /*@CompilerMessageKey*/ String err;
                    if (checker.isCommitted(xType)) {
                        err = COMMITMENT_INVALID_FIELD_WRITE_COMMITTED;
                    } else {
                        err = COMMITMENT_INVALID_FIELD_WRITE_UNCLASSIFIED;
                    }
                    checker.report(Result.failure(err, varTree), varTree);
                    return; // prevent issuing another errow about subtyping
                }
                // for field access on the current object, make sure that we don't
                // allow
                // invalid assignments. that is, even though reading this.f in a
                // constructor yields @Nullable (or similar for other typesystems),
                // it
                // is not allowed to write @Nullable to a @NonNull field.
                // This is done by first getting the type as usual (var), and then
                // again not using the postAsMember method (which takes care of
                // transforming the type of o.f for a free receiver to @Nullable)
                // (var2). Then, we take the child annotation from var2 and use it
                // for var.
                AnnotatedTypeMirror var = atypeFactory.getAnnotatedType(lhs);
                boolean old = atypeFactory.HACK_DONT_CALL_POST_AS_MEMBER;
                atypeFactory.HACK_DONT_CALL_POST_AS_MEMBER = true;
                boolean old2 = atypeFactory.shouldReadCache;
                atypeFactory.shouldReadCache = false;
                AnnotatedTypeMirror var2 = atypeFactory.getAnnotatedType(lhs);
                atypeFactory.HACK_DONT_CALL_POST_AS_MEMBER = old;
                atypeFactory.shouldReadCache = old2;
                final AnnotationMirror newAnno = var2.getAnnotationInHierarchy(
                        checker.getFieldInvariantAnnotation());
                if (newAnno != null) {
                    var.replaceAnnotation(newAnno);
                }
                checkAssignability(var, varTree);
                commonAssignmentCheck(var, valueExp, errorKey, false);
                return;
            }
        }
        super.commonAssignmentCheck(varTree, valueExp, errorKey);
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        // is this a field (and not a local variable)?
        if (TreeUtils.elementFromDeclaration(node).getKind().isField()) {
            Set<AnnotationMirror> annotationMirrors = atypeFactory.getAnnotatedType(
                    node).getExplicitAnnotations();
            // Fields cannot have commitment annotations.
            for (Class<? extends Annotation> c : checker.getInitializationAnnotations()) {
                for (AnnotationMirror a : annotationMirrors) {
                    if (checker.isUnclassified(a)) continue; // unclassified is allowed
                    if (AnnotationUtils.areSameByClass(a, c)) {
                        checker.report(Result.failure(
                                COMMITMENT_INVALID_FIELD_ANNOTATION, node),
                                node);
                        break;
                    }
                }
            }
        }
        return super.visitVariable(node, p);
    }

    @Override
    protected boolean checkContract(Receiver expr,
            AnnotationMirror necessaryAnnotation,
            AnnotationMirror inferredAnnotation, CFAbstractStore<?, ?> store) {
        // also use the information about initialized fields to check contracts
        AnnotationMirror invariantAnno = checker.getFieldInvariantAnnotation();
        if (checker.getQualifierHierarchy().isSubtype(invariantAnno,
                necessaryAnnotation)) {
            if (expr instanceof FieldAccess) {
                FieldAccess fa = (FieldAccess) expr;
                if (fa.getReceiver() instanceof ThisReference
                        || fa.getReceiver() instanceof ClassName) {
                    @SuppressWarnings("unchecked")
                    Store s = (Store) store;
                    if (s.isFieldInitialized(fa.getField())) {
                        AnnotatedTypeMirror fieldType = atypeFactory
                                .getAnnotatedType(fa.getField());
                        // is this an invariant-field?
                        if (AnnotationUtils.containsSame(
                                fieldType.getAnnotations(), invariantAnno)) {
                            return true;
                        }
                    }
                }
            }
        }
        return super.checkContract(expr, necessaryAnnotation,
                inferredAnnotation, store);
    }

    @Override
    public Void visitTypeCast(TypeCastTree node, Void p) {
        AnnotatedTypeMirror exprType = atypeFactory.getAnnotatedType(node
                .getExpression());
        AnnotatedTypeMirror castType = atypeFactory.getAnnotatedType(node);
        AnnotationMirror exprAnno = null, castAnno = null;

        // find commitment annotation
        for (Class<? extends Annotation> a : checker.getInitializationAnnotations()) {
            if (castType.hasAnnotation(a)) {
                assert castAnno == null;
                castAnno = castType.getAnnotation(a);
            }
            if (exprType.hasAnnotation(a)) {
                assert exprAnno == null;
                exprAnno = exprType.getAnnotation(a);
            }
        }

        // TODO: this is most certainly unsafe!! (and may be hiding some
        // problems)
        // If we don't find a commitment annotation, then we just assume that
        // the subtyping is alright.
        // The case that has come up is with wildcards not getting a type for
        // some reason, even though the default is @Initialized.
        boolean isSubtype;
        if (exprAnno == null || castAnno == null) {
            isSubtype = true;
        } else {
            assert exprAnno != null && castAnno != null;
            isSubtype = checker.getQualifierHierarchy().isSubtype(exprAnno,
                    castAnno);
        }

        if (!isSubtype) {
            checker.report(Result.failure(COMMITMENT_INVALID_CAST,
                    AnnotatedTypeMirror.formatAnnotationMirror(exprAnno),
                    AnnotatedTypeMirror.formatAnnotationMirror(castAnno)), node);
            return p; // suppress cast.unsafe warning
        }

        return super.visitTypeCast(node, p);
    }

    @Override
    public Void visitBlock(BlockTree node, Void p) {
        ClassTree enclosingClass = TreeUtils.enclosingClass(getCurrentPath());
        // Is this a initializer block?
        if (enclosingClass.getMembers().contains(node)) {
            if (node.isStatic()) {
                boolean isStatic = true;
                Store store = atypeFactory.getRegularExitStore(node);
                // Add field values for fields with an initializer.
                for (Pair<VariableElement, Value> t : store.getAnalysis()
                        .getFieldValues()) {
                    store.addInitializedField(t.first);
                }
                // Check that all static fields are initialized.
                List<AnnotationMirror> receiverAnnotations = Collections
                        .emptyList();
                checkFieldsInitialized(node, isStatic, store,
                        receiverAnnotations);
            }
        }
        return super.visitBlock(node, p);
    }

    protected List<VariableTree> initializedFields = new ArrayList<>();
    @Override
    public Void visitClass(ClassTree node, Void p) {

        // call the ATF with any node from this class to trigger the dataflow
        // analysis.
        atypeFactory.getAnnotatedType(node);

        // go through all members and look for initializers.
        // save all fields that are initialized and do not report errors about
        // them later when checking constructors.
        for (Tree member : node.getMembers()) {
            if (member instanceof BlockTree && !((BlockTree) member).isStatic()) {
                BlockTree block = (BlockTree) member;
                Store store = atypeFactory.getRegularExitStore(block);
                if (store != null) {
                    // Add field values for fields with an initializer.
                    for (Pair<VariableElement, Value> t : store.getAnalysis()
                            .getFieldValues()) {
                        store.addInitializedField(t.first);
                    }
                    final List<VariableTree> init = atypeFactory
                            .getInitializedInvariantFields(store,
                                    getCurrentPath());
                    initializedFields.addAll(init);
                }
            }
        }

        Void result = super.visitClass(node, p);

        // Is there a static initializer block?
        boolean hasStaticInitializer = false;
        for (Tree t : node.getMembers()) {
            switch (t.getKind()) {
            case BLOCK:
                if (((BlockTree) t).isStatic()) {
                    hasStaticInitializer = true;
                }
                break;

            default:
                break;
            }
        }

        // Warn about uninitialized static fields if there is no static
        // initializer (otherwise, errors are reported there).
        if (!hasStaticInitializer && node.getKind() == Kind.CLASS) {
            boolean isStatic = true;
            Store store = atypeFactory.getEmptyStore();
            // Add field values for fields with an initializer.
            for (Pair<VariableElement, Value> t : store.getAnalysis()
                    .getFieldValues()) {
                store.addInitializedField(t.first);
            }
            List<AnnotationMirror> receiverAnnotations = Collections
                    .emptyList();
            checkFieldsInitialized(node, isStatic, store, receiverAnnotations);
        }

        return result;
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        if (TreeUtils.isConstructor(node)) {
            Collection<? extends AnnotationMirror> returnTypeAnnotations = getExplicitReturnTypeAnnotations(node);
            // check for invalid constructor return type
            for (Class<? extends Annotation> c : checker.getInvalidConstructorReturnTypeAnnotations()) {
                for (AnnotationMirror a : returnTypeAnnotations) {
                    if (AnnotationUtils.areSameByClass(a, c)) {
                        checker.report(Result.failure(
                                COMMITMENT_INVALID_CONSTRUCTOR_RETURN_TYPE,
                                node), node);
                        break;
                    }
                }
            }

            // Check that all fields have been initialized at the end of the
            // constructor.
            boolean isStatic = false;
            Store store = atypeFactory.getRegularExitStore(node);
            List<? extends AnnotationMirror> receiverAnnotations = getAllReceiverAnnotations(node);
            checkFieldsInitialized(node, isStatic, store, receiverAnnotations);
        }
        return super.visitMethod(node, p);
    }

    /**
     * Returns the full list of annotations on the receiver.
     */
    private List<? extends AnnotationMirror> getAllReceiverAnnotations(
            MethodTree node) {
        // TODO: get access to a Types instance and use it to get receiver type
        // Or, extend ExecutableElement with such a method.
        // Note that we cannot use the receiver type from
        // AnnotatedExecutableType,
        // because that would only have the nullness annotations; here we want
        // to
        // see all annotations on the receiver.
        List<? extends AnnotationMirror> rcvannos;
        if (TreeUtils.isConstructor(node)) {
            com.sun.tools.javac.code.Symbol meth = (com.sun.tools.javac.code.Symbol) TreeUtils
                    .elementFromDeclaration(node);
            rcvannos = meth.getRawTypeAttributes();
            if (rcvannos == null) {
                rcvannos = Collections.<AnnotationMirror> emptyList();
            }
        } else {
            ExecutableElement meth = TreeUtils.elementFromDeclaration(node);
            com.sun.tools.javac.code.Type rcv = (com.sun.tools.javac.code.Type) ((ExecutableType) meth
                    .asType()).getReceiverType();
            if (rcv != null && rcv.isAnnotated()) {
                rcvannos = ((com.sun.tools.javac.code.Type.AnnotatedType)rcv).getAnnotationMirrors();
            } else {
                rcvannos = Collections.<AnnotationMirror> emptyList();
            }
        }
        return rcvannos;
    }

    /**
     * Checks that all fields (all static fields if {@code staticFields} is
     * true) are initialized in the given store.
     */
    protected void checkFieldsInitialized(Tree blockNode, boolean staticFields,
            Store store, List<? extends AnnotationMirror> receiverAnnotations) {
        // If the store is null, then the constructor cannot terminate
        // successfully
        if (store != null) {
            List<VariableTree> violatingFields = atypeFactory.getUninitializedInvariantFields(store, getCurrentPath(),
                            staticFields, receiverAnnotations);
            if (!staticFields) {
                // remove fields that have already been initialized by an
                // initializer block
                violatingFields.removeAll(initializedFields);
            }
            if (!violatingFields.isEmpty()) {
                StringBuilder fieldsString = new StringBuilder();
                boolean first = true;
                for (VariableTree f : violatingFields) {
                    if (!first) {
                        fieldsString.append(", ");
                    }
                    first = false;
                    fieldsString.append(f.getName());
                }
                checker.report(Result.failure(COMMITMENT_FIELDS_UNINITIALIZED,
                        fieldsString), blockNode);
            }
        }
    }

    public Set<AnnotationMirror> getExplicitReturnTypeAnnotations(MethodTree node) {
        AnnotatedTypeMirror t = atypeFactory.fromMember(node);
        assert t instanceof AnnotatedExecutableType;
        AnnotatedExecutableType type = (AnnotatedExecutableType) t;
        return type.getReturnType().getAnnotations();
    }
}
