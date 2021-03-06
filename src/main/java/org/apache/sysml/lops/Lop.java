/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.lops;

import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysml.lops.LopProperties.ExecLocation;
import org.apache.sysml.lops.LopProperties.ExecType;
import org.apache.sysml.lops.compile.Dag;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;


/**
 * Base class for all Lops.
 */

public abstract class Lop 
{
	
	public enum Type {
		Data, DataGen,                                      //CP/MR read/write/datagen 
		ReBlock, CSVReBlock,                                //MR reblock operations
		MMCJ, MMRJ, MMTSJ, PMMJ, MapMult, MapMultChain,     //MR matrix multiplications
		UnaryCP, UNARY, BinaryCP, Binary, Ternary, Nary,    //CP/MR unary/binary/ternary
		RightIndex, LeftIndex, ZeroOut,                     //CP/MR indexing 
		Aggregate, PartialAggregate,                        //CP/MR aggregation
		BinUaggChain, UaggOuterChain,                       //CP/MR aggregation
		TernaryAggregate,                                   //CP ternary-binary aggregates
		Grouping,                                           //MR grouping
		Append,                                             //CP/MR append (column append)
		CombineUnary, CombineBinary, CombineTernary,        //MR combine (stitch together)
		CentralMoment, CoVariance, GroupedAgg, GroupedAggM,
		Transform, DataPartition, RepMat,                   //CP/MR reorganization, partitioning, replication
		ParameterizedBuiltin,                               //CP/MR parameterized ops (name/value)
		FunctionCallCP, FunctionCallCPSingle,               //CP function calls 
		CumulativePartialAggregate, CumulativeSplitAggregate, CumulativeOffsetBinary, //MR cumsum/cumprod/cummin/cummax
		WeightedSquaredLoss, WeightedSigmoid, WeightedDivMM, WeightedCeMM, WeightedUMM,
		SortKeys, PickValues,
		Checkpoint,                                         //Spark persist into storage level
		PlusMult, MinusMult,                                //CP
		SpoofFused,                                         //CP/SP generated fused operator
	}

	/**
	 * Lop types
	 */
	public enum SimpleInstType {
		Scalar
	}

	public enum VisitStatus {
		DONE, NOTVISITED
	}
	

	protected static final Log LOG =  LogFactory.getLog(Lop.class.getName());
	
	public static final String FILE_SEPARATOR = "/";
	public static final String PROCESS_PREFIX = "_p";
	
	//special delimiters w/ extended ASCII characters to avoid collisions 
	public static final String INSTRUCTION_DELIMITOR = "\u2021";
	public static final String OPERAND_DELIMITOR = "\u00b0"; 
	public static final String VALUETYPE_PREFIX = "\u00b7" ; 
	public static final String DATATYPE_PREFIX = VALUETYPE_PREFIX; 
	public static final String LITERAL_PREFIX = VALUETYPE_PREFIX; 
	public static final String VARIABLE_NAME_PLACEHOLDER = "\u00b6"; 
	
	public static final String NAME_VALUE_SEPARATOR = "="; // e.g., used in parameterized builtins
	public static final String MATRIX_VAR_NAME_PREFIX = "_mVar";
	public static final String FRAME_VAR_NAME_PREFIX = "_fVar";
	public static final String SCALAR_VAR_NAME_PREFIX = "_Var";
	public static final String UPDATE_INPLACE_PREFIX = "_uip";
	
	// Boolean array to hold the list of nodes(lops) in the DAG that are reachable from this lop.
	private boolean[] reachable = null;
	private DataType _dataType;
	private ValueType _valueType;

	private VisitStatus _visited = VisitStatus.NOTVISITED;

	protected Lop.Type type;

	/**
	 * handle to all inputs and outputs.
	 */
	protected ArrayList<Lop> inputs;
	protected ArrayList<Lop> outputs;
	
	/**
	 * refers to #lops whose input is equal to the output produced by this lop.
	 * This is used in generating rmvar instructions as soon as the output produced
	 * by this lop is consumed. Otherwise, such rmvar instructions are added 
	 * at the end of program blocks. 
	 * 
	 */
	protected int consumerCount;

	/**
	 * handle to output parameters, dimensions, blocking, etc.
	 */

	protected OutputParameters outParams = null;

	protected LopProperties lps = null;
	

	/**
	 * Constructor to be invoked by base class.
	 * 
	 * @param t lop type
	 * @param dt data type
	 * @param vt value type
	 */
	public Lop(Type t, DataType dt, ValueType vt) {
		type = t;
		_dataType = dt; // data type of the output produced from this LOP
		_valueType = vt; // value type of the output produced from this LOP
		inputs = new ArrayList<>();
		outputs = new ArrayList<>();
		outParams = new OutputParameters();
		lps = new LopProperties();
	}
	
	/**
	 * get visit status of node
	 * 
	 * @return visit status
	 */

	public VisitStatus getVisited() {
		return _visited;
	}

	/**
	 * set visit status of node
	 * 
	 * @param visited visit status
	 */
	public void setVisited(VisitStatus visited) {
		_visited = visited;
	}

	
	public boolean[] get_reachable() {
		return reachable;
	}

	public boolean[] create_reachable(int size) {
		reachable = new boolean[size];
		return reachable;
	}

	/**
	 * get data type of the output that is produced by this lop
	 * 
	 * @return data type
	 */

	public DataType getDataType() {
		return _dataType;
	}

	/**
	 * set data type of the output that is produced by this lop
	 * 
	 * @param dt data type
	 */
	public void setDataType(DataType dt) {
		_dataType = dt;
	}

	/**
	 * get value type of the output that is produced by this lop
	 * 
	 * @return value type
	 */

	public ValueType getValueType() {
		return _valueType;
	}

	/**
	 * set value type of the output that is produced by this lop
	 * 
	 * @param vt value type
	 */
	public void setValueType(ValueType vt) {
		_valueType = vt;
	}


	/**
	 * Method to get Lop type.
	 * 
	 * @return lop type
	 */

	public Lop.Type getType() {
		return type;
	}

	/**
	 * Method to get input of Lops
	 * 
	 * @return list of input lops
	 */
	public ArrayList<Lop> getInputs() {
		return inputs;
	}

	/**
	 * Method to get output of Lops
	 * 
	 * @return list of output lops
	 */

	public ArrayList<Lop> getOutputs() {
		return outputs;
	}

	/**
	 * Method to add input to Lop
	 * 
	 * @param op input lop
	 */

	public void addInput(Lop op) {
		inputs.add(op);
	}

	/**
	 * Method to add output to Lop
	 * 
	 * @param op output lop
	 */

	public void addOutput(Lop op) {
		outputs.add(op);
	}
	
	public int getConsumerCount() {
		return consumerCount;
	}
	
	public void setConsumerCount(int cc) {
		consumerCount = cc;
	}
	
	public int removeConsumer() {
		consumerCount--;
		return consumerCount;
	}

	/**
	 * Method to have Lops print their state. This is for debugging purposes.
	 */
	@Override
	public abstract String toString();

	public void resetVisitStatus() {
		if (this.getVisited() == Lop.VisitStatus.NOTVISITED)
			return;
		for (int i = 0; i < this.getInputs().size(); i++) {
			this.getInputs().get(i).resetVisitStatus();
		}
		this.setVisited(Lop.VisitStatus.NOTVISITED);
	}

	/**
	 * Method to have recursively print state of Lop graph.
	 */

	public final void printMe() {
		if (LOG.isDebugEnabled()){
			StringBuilder s = new StringBuilder("");
			if (this.getVisited() != VisitStatus.DONE) {
				s.append(getType() + ": " + getID() + "\n" ); // hashCode());
				s.append("Inputs: ");
				for (int i = 0; i < this.getInputs().size(); i++) {
					s.append(" " + this.getInputs().get(i).getID() + " ");
				}

				s.append("\n");
				s.append("Outputs: ");
				for (int i = 0; i < this.getOutputs().size(); i++) {
					s.append(" " + this.getOutputs().get(i).getID() + " ");
				}

				s.append("\n");
				s.append(this.toString());
				s.append("Begin Line: " + _beginLine + ", Begin Column: " + _beginColumn + ", End Line: " + _endLine + ", End Column: " + _endColumn + "\n");
				s.append("FORMAT:" + this.getOutputParameters().getFormat() + ", rows="
						+ this.getOutputParameters().getNumRows() + ", cols=" + this.getOutputParameters().getNumCols()
						+ ", Blocked?: " + this.getOutputParameters().isBlocked() + ", rowsInBlock=" + 
						this.getOutputParameters().getRowsInBlock() + ", colsInBlock=" + 
						this.getOutputParameters().getColsInBlock() + "\n");
				this.setVisited(VisitStatus.DONE);
				s.append("\n");

				for (int i = 0; i < this.getInputs().size(); i++) {
					this.getInputs().get(i).printMe();
				}
			}
			LOG.debug(s.toString());
		}
	}

	/**
	 * Method to return the ID of LOP
	 * 
	 * @return lop ID
	 */
	public long getID() {
		return lps.getID();
	}
	
	public int getLevel() {
		return lps.getLevel();
	}
	
	protected void setLevel() {
		lps.setLevel(inputs);
	}
	
	/**
	 * Method to get the location property of LOP
	 * 
	 * @return location
	 */
 	public ExecLocation getExecLocation() {
		return lps.getExecLocation();
	}
 
	/**
	 * Method to get the execution type (CP, CP_FILE, MR, SPARK, GPU, INVALID) of LOP
	 * 
	 * @return execution type
	 */
 	public ExecType getExecType() {
		return lps.getExecType();
	}
 
	/**
	 * Method to get the compatible job type for the LOP
	 * 
	 * @return compatible job type
	 */
	
	public int getCompatibleJobs() {
		return lps.getCompatibleJobs();
	}
	
	/**
	 * Method to find if the lop breaks alignment
	 * 
	 * @return true if lop breaks alignment
	 */
	public boolean getBreaksAlignment() {
		return lps.getBreaksAlignment();
	}
	
	public boolean getProducesIntermediateOutput() {
		return lps.getProducesIntermediateOutput();
	}
	
	public boolean isAligner()
	{
		return lps.isAligner();
	}

	public boolean definesMRJob()
	{
		return lps.getDefinesMRJob();
	}

	/**
	 * Method to recursively add LOPS to a DAG
	 * 
	 * @param dag lop DAG
	 */
	public final void addToDag(Dag<Lop> dag) 
	{
		if( dag.addNode(this) )
			for( Lop l : getInputs() )
				l.addToDag(dag);
	}

	/**
	 * Method to get output parameters
	 * 
	 * @return output parameters
	 */

	public OutputParameters getOutputParameters() {
		return outParams;
	}
	

	/** Method should be overridden if needed
	 * 
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	/** Method should be overridden if needed
	 * 
	 * @param input1 input 1
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String input1, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/** Method should be overridden if needed
	 * 
	 * @param input1 input 1
	 * @param input2 input 2
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String input1, String input2, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	/**
	 * Method should be overridden if needed
	 * 
	 * @param input1 input 1
	 * @param input2 input 2
	 * @param input3 input 3
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String input1, String input2, String input3, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	/**
	 * Method should be overridden if needed
	 * 
	 * @param input1 input 1
	 * @param input2 input 2
	 * @param input3 input 3
	 * @param input4 input 4
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String input1, String input2, String input3, String input4, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/**
	 * Method should be overridden if needed
	 * 
	 * @param input1 input 1
	 * @param input2 input 2
	 * @param input3 input 3
	 * @param input4 input 4
	 * @param input5 input 5
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String input1, String input2, String input3, String input4, String input5, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/** Method should be overridden if needed
	 * 
	 * @param input1 input 1
	 * @param input2 input 2
	 * @param input3 input 3
	 * @param input4 input 4
	 * @param input5 input 5
	 * @param input6 input 6
	 * @param output output
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String input1, String input2, String input3, String input4, String input5, String input6, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	public String getInstructions(String input1, String input2, String input3, String input4, String input5, String input6, String input7, String output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	public String getInstructions(String[] inputs, String outputs) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	
	public String getInstructions(int output_index) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass. Lop Type: " + this.getType());
	}

	public String getInstructions(int input_index, int output_index) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass. Lop Type: " + this.getType());
	}

	/** Method should be overridden if needed
	 * 
	 * @param input_index1 input index 1
	 * @param input_index2 input index 2
	 * @param output_index output index
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(int input_index1, int input_index2, int output_index) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/** Method should be overridden if needed
	 * 
	 * @param input_index1 input index 1
	 * @param input_index2 input index 2
	 * @param input_index3 input index 3
	 * @param output_index output index
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(int input_index1, int input_index2, int input_index3, int output_index) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	/** Method should be overridden if needed
	 * 
	 * @param input_index1 input index 1
	 * @param input_index2 input index 2
	 * @param input_index3 input index 3
	 * @param input_index4 input index 4
	 * @param output_index output index
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(int input_index1, int input_index2, int input_index3, int input_index4, int output_index) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/** Method should be overridden if needed
	 * 
	 * @param input_index1 input index 1
	 * @param input_index2 input index 2
	 * @param input_index3 input index 3
	 * @param input_index4 input index 4
	 * @param input_index5 input index 5
	 * @param output_index output index
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(int input_index1, int input_index2, int input_index3, int input_index4, int input_index5, int output_index) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}


	/** Method should be overridden if needed
	 * 
	 * @param inputs array of inputs
	 * @param outputs array of outputs
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions(String[] inputs, String[] outputs) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}
	
	/** Method should be overridden if needed
	 * 
	 * @return instructions as string
	 * @throws LopsException if LopsException occurs
	 */
	public String getInstructions() throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/** Method should be overridden if needed
	 * 
	 * @return simple instruction type
	 * @throws LopsException if LopsException occurs
	 */
	public SimpleInstType getSimpleInstructionType() throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	///////////////////////////////////////////////////////////////////////////
	// store position information for Lops
	///////////////////////////////////////////////////////////////////////////
	public int _beginLine, _beginColumn;
	public int _endLine, _endColumn;
	public String _filename;
	
	public void setBeginLine(int passed)    { _beginLine = passed;   }
	public void setBeginColumn(int passed) 	{ _beginColumn = passed; }
	public void setEndLine(int passed) 		{ _endLine = passed;   }
	public void setEndColumn(int passed)	{ _endColumn = passed; }
	public void setFilename(String passed) { _filename = passed; }
	
	public void setAllPositions(String filename, int blp, int bcp, int elp, int ecp){
		_filename = filename;
		_beginLine	 = blp; 
		_beginColumn = bcp; 
		_endLine 	 = elp;
		_endColumn 	 = ecp;
	}

	public int getBeginLine()	{ return _beginLine;   }
	public int getBeginColumn() { return _beginColumn; }
	public int getEndLine() 	{ return _endLine;   }
	public int getEndColumn()	{ return _endColumn; }
	public String getFilename()	{ return _filename; }
	
	public String printErrorLocation(){
		return "ERROR: line " + _beginLine + ", column " + _beginColumn + " -- ";
	}

	public String getInstructions(int input, int rowl, int rowu,
			int coll, int colu, int leftRowDim,
			int leftColDim, int output) throws LopsException {
		throw new LopsException(this.printErrorLocation() + "Should never be invoked in Baseclass");
	}

	/**
	 * Function that determines if the output of a LOP is defined by a variable or not.
	 * 
	 * @return true if lop output defined by a variable
	 */
	public boolean isVariable() {
		return ( (getExecLocation() == ExecLocation.Data && !((Data)this).isLiteral()) 
				 || !(getExecLocation() == ExecLocation.Data ) );
	}
	
	
	
	/**
	 * Method to prepare instruction operand with given parameters.
	 * 
	 * @param label instruction label
	 * @param dt data type
	 * @param vt value type
	 * @return instruction operand with data type and value type
	 */
	public String prepOperand(String label, DataType dt, ValueType vt) {
		StringBuilder sb = new StringBuilder();
		sb.append(label);
		sb.append(Lop.DATATYPE_PREFIX);
		sb.append(dt);
		sb.append(Lop.VALUETYPE_PREFIX);
		sb.append(vt);
		return sb.toString();
	}

	/**
	 * Method to prepare instruction operand with given parameters.
	 * 
	 * @param label instruction label
	 * @param dt data type
	 * @param vt value type
	 * @param literal true if literal
	 * @return instruction operand with data type, value type, and literal status
	 */
	public String prepOperand(String label, DataType dt, ValueType vt, boolean literal) {
		StringBuilder sb = new StringBuilder();
		sb.append(label);
		sb.append(Lop.DATATYPE_PREFIX);
		sb.append(dt);
		sb.append(Lop.VALUETYPE_PREFIX);
		sb.append(vt);
		sb.append(Lop.LITERAL_PREFIX);
		sb.append(literal);
		return sb.toString();
	}

	/**
	 * Method to prepare instruction operand with given label. Data type
	 * and Value type are derived from Lop's properties.
	 * 
	 * @param label instruction label
	 * @return instruction operand with data type and value type
	 */
	private String prepOperand(String label) {
		StringBuilder sb = new StringBuilder("");
		sb.append(label);
		sb.append(Lop.DATATYPE_PREFIX);
		sb.append(getDataType());
		sb.append(Lop.VALUETYPE_PREFIX);
		sb.append(getValueType());
		return sb.toString();
	}
	
	public String prepOutputOperand() {
		return prepOperand(getOutputParameters().getLabel());
	}
	
	public String prepOutputOperand(int index) {
		return prepOperand(String.valueOf(index));
	}
	public String prepOutputOperand(String label) {
		return prepOperand(label);
	}
	
	/**
	 * Function to prepare label for scalar inputs while generating instructions.
	 * It attaches placeholder suffix and prefixes if the Lop denotes a variable.
	 * 
	 * @return prepared scalar label
	 */
	public String prepScalarLabel() {
		String ret = getOutputParameters().getLabel();
		if ( isVariable() ){
			ret = Lop.VARIABLE_NAME_PLACEHOLDER + ret + Lop.VARIABLE_NAME_PLACEHOLDER;
		}
		return ret;
	}
	
	/**
	 * Function to be used in creating instructions for creating scalar
	 * operands. It decides whether or not attach placeholders for instruction
	 * patching. Resulting string also encodes if the operand is a literal.
	 * 
	 * For non-literals: 
	 * Placeholder prefix and suffix need to be attached for Instruction 
	 * Patching during execution. However, they should NOT be attached IF: 
	 *   - the operand is a literal 
	 *     OR 
	 *   - the execution type is CP. This is because CP runtime has access 
	 *     to symbol table and the instruction encodes sufficient information
	 *     to determine if an operand is a literal or not.
	 * 
	 * @param et execution type
	 * @param label instruction label
	 * @return prepared scalar operand
	 */
	public String prepScalarOperand(ExecType et, String label) {
		boolean isData = (getExecLocation() == ExecLocation.Data);
		boolean isLiteral = (isData && ((Data)this).isLiteral());
		
		StringBuilder sb = new StringBuilder("");
		if ( et == ExecType.CP || et == ExecType.SPARK || et == ExecType.GPU || (isData && isLiteral)) {
			sb.append(label);
		}
		else {
			sb.append(Lop.VARIABLE_NAME_PLACEHOLDER);
			sb.append(label);
			sb.append(Lop.VARIABLE_NAME_PLACEHOLDER);
		}
		
		sb.append(Lop.DATATYPE_PREFIX);
		sb.append(getDataType());
		sb.append(Lop.VALUETYPE_PREFIX);
		sb.append(getValueType());
		sb.append(Lop.LITERAL_PREFIX);
		sb.append(isLiteral);
		
		return sb.toString();
	}

	public String prepScalarInputOperand(ExecType et) {
		return prepScalarOperand(et, getOutputParameters().getLabel());
	}
	
	public String prepScalarInputOperand(String label) {
		boolean isData = (getExecLocation() == ExecLocation.Data);
		boolean isLiteral = (isData && ((Data)this).isLiteral());
		
		StringBuilder sb = new StringBuilder("");
		sb.append(label);
		sb.append(Lop.DATATYPE_PREFIX);
		sb.append(getDataType());
		sb.append(Lop.VALUETYPE_PREFIX);
		sb.append(getValueType());
		sb.append(Lop.LITERAL_PREFIX);
		sb.append(isLiteral);
		
		return sb.toString();
	}

	public String prepInputOperand(int index) {
		return prepInputOperand(String.valueOf(index));
	}

	public String prepInputOperand(String label) {
		DataType dt = getDataType();
		if ( dt == DataType.MATRIX ) {
			return prepOperand(label);
		}
		else {
			return prepScalarInputOperand(label);
		}
	}
	
	/**
	 * Method to check if a LOP expects an input from the Distributed Cache.
	 * The method in parent class always returns <code>false</code> (default).
	 * It must be overridden by individual LOPs that use the cache.
	 * 
	 * @return true if lop expects input from distributed cache. In LOP class, always returns false.
	 */
	public boolean usesDistributedCache() {
		return false;
	}
	
	public int[] distributedCacheInputIndex() {
		return new int[]{-1};
	}

	
	public boolean hasNonBlockedInputs() {
		for(Lop in : getInputs())
			if(in.getDataType() == DataType.MATRIX && !in.getOutputParameters().isBlocked())
				return true;
		return false;
	}
}
