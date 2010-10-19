package org.jruby.compiler.ir.instructions;

import java.util.Map;
import org.jruby.RubyProc;

import org.jruby.compiler.ir.Operation;
import org.jruby.compiler.ir.operands.MethAddr;
import org.jruby.compiler.ir.operands.MetaObject;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.operands.StringLiteral;
import org.jruby.compiler.ir.operands.Variable;
import org.jruby.compiler.ir.IRClass;
import org.jruby.compiler.ir.IRClosure;
import org.jruby.compiler.ir.IRModule;
import org.jruby.compiler.ir.IRMethod;
import org.jruby.compiler.ir.IRScope;
import org.jruby.compiler.ir.operands.SelfVariable;
import org.jruby.compiler.ir.representations.InlinerInfo;
import org.jruby.interpreter.InterpreterContext;
import org.jruby.runtime.Block;
import org.jruby.runtime.builtin.IRubyObject;

/*
 * args field: [self, reciever, *args]
 */
public class CallInstr extends MultiOperandInstr {
    Operand _methAddr;
    Operand _closure;
    
    private boolean _flagsComputed;
    private boolean _canBeEval;
    private boolean _requiresFrame;
    private int _numArgs;

    public CallInstr(Variable result, Operand methAddr, Operand[] args, Operand closure) {
        super(Operation.CALL, result, buildAllArgs(methAddr, closure, args));
        _methAddr = methAddr;
        _closure = closure;
        _flagsComputed = false;
        _canBeEval = true;
        _requiresFrame = true;
        _numArgs = args.length;
    }

    public CallInstr(Operation op, Variable result, Operand methAddr, Operand[] args, Operand closure) {
        super(op, result, buildAllArgs(methAddr, closure, args));
        _methAddr = methAddr;
        _closure = closure;
        _flagsComputed = false;
        _canBeEval = true;
        _requiresFrame = true;
        _numArgs = args.length;
    }

    public Operand getMethodAddr() {
        return _methAddr;
    }

    public Operand getClosureArg() {
        return _closure;
    }

    public Operand getReceiver() {
        return _args[1];
    }

    public int getNumArgs() {
        return _numArgs;
    }

    // ENEBO: Can be System.arraycopy
    // Beware: Expensive call since a new array is allocated on each call.
    public Operand[] getCallArgs() {
        Operand[] callArgs = new Operand[_numArgs];
        
        for (int i = 0; i < _numArgs; i++) {
            callArgs[i] = _args[i + 1];
        }

        return callArgs;
    }

    public Operand[] cloneCallArgs(InlinerInfo ii) {
        Operand[] clonedArgs = new Operand[_numArgs];

        for (int i = 0; i < _numArgs; i++) {
            clonedArgs[i] = _args[i + 1].cloneForInlining(ii);
        }

        return clonedArgs;
    }

    @Override
    public void simplifyOperands(Map<Operand, Operand> valueMap) {
        super.simplifyOperands(valueMap);
        _methAddr = _args[0];
        _closure = (_closure == null) ? null : _args[_args.length - 1];
        _flagsComputed = false; // Forces recomputation of flags
    }

    public boolean isRubyInternalsCall() {
        return false;
    }

    public boolean isStaticCallTarget() {
        return getTargetMethod() != null;
    }

    // SSS FIXME: Right now, this code is not very smart!
    // In a JIT context, we might be compiling this call in the context of a surrounding PIC (or a monomorphic IC).
    // If so, the receiver type and hence the target method will be known.
    public IRMethod getTargetMethodWithReceiver(Operand receiver) {
        if (!(_methAddr instanceof MethAddr)) return null;

        String mname = ((MethAddr) _methAddr).getName();

        if (receiver instanceof MetaObject) {
            IRModule m = (IRModule) (((MetaObject) receiver).scope);
            return m.getClassMethod(mname);
        } // self.foo(..);
        // If this call instruction is in a class method, we'll fetch a class method
        // If this call instruction is in an instance method, we'll fetch an instance method
        else if (receiver instanceof SelfVariable) {
            return null;
        } else {
            IRClass c = receiver.getTargetClass();

            return c == null ? null : c.getInstanceMethod(mname);
        }
    }

    public IRMethod getTargetMethod() {
        return getTargetMethodWithReceiver(getReceiver());
    }

    // Can this call lead to ruby code getting modified?  
    // If we don't know what method we are calling, we assume it can (pessimistic, but safe!)
    // If we do know the target method, we ask the method itself whether it modifies ruby code
    public boolean canModifyCode() {
        IRMethod method = getTargetMethod();

        return method == null ? true : method.modifiesCode();
    }

    private boolean getEvalFlag() {
        Operand ma = getMethodAddr();

        // ENEBO: This could be made into a recursive two-method thing so then: send(:send, :send, :send, :send, :eval, "Hosed") works
        // ENEBO: This is not checking for __send__
        if (ma instanceof MethAddr) {
            String mname = ((MethAddr) ma).getName();
            // checking for "call" is conservative.  It can be eval only if the receiver is a Method
            if (mname.equals("call") || mname.equals("eval")) return true;

            // Calls to 'send' where the first arg is either unknown or is eval or send (any others?)
            if (mname.equals("send")) {
                Operand[] args = getCallArgs();
                if (args.length >= 2) {
                    Operand meth = args[1];
                    if (!(meth instanceof StringLiteral)) return true; // We don't know

                    // But why?  Why are you killing yourself (and us) doing this?
                    String name = ((StringLiteral) meth)._str_value;
                    if (name.equals("call") || name.equals("eval") || name.equals("send")) return true;
                }
            }
            
            return false; // All checks passed
        } 
        
        return true; // Unknown method -- could be eval!
    }

    private boolean getRequiresFrameFlag() {
        // This is an eval, or it has a closure that requires a frame
        if (canBeEval()) return true;

        if (_closure != null) {
            // can be a symbol .. ex: [1,2,3,4].map(&:foo) .. &:foo is a closure
            if (!(_closure instanceof MetaObject)) return false;

            IRClosure cl = (IRClosure) ((MetaObject) _closure).scope;

            if (cl.requiresFrame()) return true;
        }

        // Check if we are calling Proc.new or lambda
        Operand ma = getMethodAddr();
        // Unknown target -- could be lambda or Proc.new
        if (!(ma instanceof MethAddr)) return true;

        String mname = ((MethAddr) ma).getName();

        if (mname.equals("lambda")) return true;

        if (mname.equals("new")) {
            Operand receiver = getReceiver();

            // Unknown receiver -- could be Proc!!
            if (!(receiver instanceof MetaObject)) return true;

            IRScope c = ((MetaObject) receiver).scope;

            if ((c instanceof IRClass) && (((IRClass) c).name.equals("Proc"))) return true;
        }
        
        return false;  // All checks done -- dont need one
    }

    private void computeFlags() {
        // Order important!
        _flagsComputed = true;
        _canBeEval = getEvalFlag();
        _requiresFrame = getRequiresFrameFlag();
    }

    public boolean canBeEval() {
        if (!_flagsComputed) computeFlags();

        return _canBeEval;
    }

    public boolean requiresFrame() {
        if (!_flagsComputed) computeFlags();

        return _requiresFrame;
    }

    public boolean canCaptureCallersFrame() {
        /**
         * We should do this better by setting default flags for various core library methods
         * and by checking type of receiver to see if the receiver is any core object (string, array, etc.)
         *
        if (_methAddr instanceof MethAddr) {
        String n = ((MethAddr)_methAddr).getName();
        return !n.equals("each") && !n.equals("inject") && !n.equals("+") && !n.equals("*") && !n.equals("+=") && !n.equals("*=");
        }
         **/
        Operand r = getReceiver();
        IRMethod rm = getTargetMethodWithReceiver(r);

        // If we don't know the method we are dispatching to, or if we know that the method can capture the callers frame,
        // we are in deep doo-doo.  We will need to store all variables in the call frame.
        return ((rm == null) || rm.canCaptureCallersFrame());
    }

    public boolean isLVADataflowBarrier() {
        // If the call is an eval, OR if it passes a closure and the callee can capture the caller's frame, we are in trouble
        // We would have to pretty much spill everything at the call site!
        return canBeEval() || ((getClosureArg() != null) && canCaptureCallersFrame());
    }

    @Override
    public String toString() {
        return "\t"
                + (result == null ? "" : result + " = ")
                + operation + "(" + _methAddr + ", " + java.util.Arrays.toString(getCallArgs())
                + (_closure == null ? "" : ", &" + _closure) + ")";
    }

    public Instr cloneForInlining(InlinerInfo ii) {
        return new CallInstr(ii.getRenamedVariable(result), _methAddr.cloneForInlining(ii), cloneCallArgs(ii), _closure == null ? null : _closure.cloneForInlining(ii));
	}

// --------------- Private methods ---------------

    private static Operand[] buildAllArgs(Operand methAddr, Operand closure, Operand[] callArgs) {
        Operand[] allArgs = new Operand[callArgs.length + 1 + ((closure != null) ? 1 : 0)];

        allArgs[0] = methAddr;
        for (int i = 0; i < callArgs.length; i++) {
            allArgs[i + 1] = callArgs[i];
        }
        
        if (closure != null) allArgs[callArgs.length + 1] = closure;

        return allArgs;
    }

    @Override
    public void interpret(InterpreterContext interp, IRubyObject self) {
        IRubyObject receiver = (IRubyObject) getReceiver().retrieve(interp);
        String name = (String) _methAddr.retrieve(interp);        // TODO: What happens when _methAddr is not actually a name?
        Object resultValue;

        if (_closure == null) {
            resultValue = receiver.callMethod(interp.getContext(), name, prepareArguments(interp));
        } else {
            resultValue = receiver.callMethod(interp.getContext(), name, prepareArguments(interp), prepareBlock(interp));
        }

        getResult().store(interp, resultValue);
    }

    private Block prepareBlock(InterpreterContext interp) {
        Object value = _closure.retrieve(interp);
        return value instanceof RubyProc ? ((RubyProc) value).getBlock() : (Block) value;
    }

    public IRubyObject[] prepareArguments(InterpreterContext interp) {
        Operand[] operands = getOperands();
        int closureOffset = _closure == null ? 0 : 1;
        IRubyObject[] args = new IRubyObject[operands.length - 2 - closureOffset];
        int length = args.length - closureOffset;

        for (int i = 0; i < length; i++) {
            args[i] = (IRubyObject) operands[i + 2].retrieve(interp);
        }

        System.out.println("ARGS>LENGTH " + args.length);
        System.out.println("ARGS: " + java.util.Arrays.toString(args));
        return args;
    }
}