/**
 * This is the default Node type used to create the tree and is currently used as is for rendering callTree view,
 * TODO: Based on other work_types, new fields may be required to be added
 * Created by rohit.patiyal on 19/04/17.
 */
export default class FrameNode{
  constructor (name, childCount, lineNo, onStack, onCPU){
    this.name = name;
    this.childCount = childCount;
    this.lineNo = lineNo;
    this.onStack = onStack;
    this.onCPU = onCPU;
    this.children = [];
  }
  hasParent(){
    return (this.parent !== undefined);
  }
}
