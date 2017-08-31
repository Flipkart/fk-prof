/**
 * Created by rohit.patiyal on 21/04/17.
 */

export default class HotMethodNode{
  constructor(belongsToTopLayer, lineNo, name, sampledCallCount = 0, parentsWithSampledCallCount = []){
    this.belongsToTopLayer = belongsToTopLayer;
    this.lineNo = lineNo;
    this.name = name;
    this.sampledCallCount = sampledCallCount;
    this.parentsWithSampledCallCount = parentsWithSampledCallCount;
  }

  identifier(){
    if(!this.belongsToTopLayer){
      return `${this.name}:${this.lineNo}`;
    }else{
      return this.name;
    }
  }
}


