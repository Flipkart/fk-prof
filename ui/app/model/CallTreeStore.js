import http from 'utils/http';
export default class CallTreeStore {
  constructor(url) {
    this.nodes = {};
    this.methodLookup = {};
    this.url = url;
  }

  flatten(root, bodyRoot) {
    //sorting logic on Object.entries if any
    Object.entries(root).forEach(([k, v]) => {
      if (k !== 'method_lookup') {
        this.nodes[parseInt(k)] = [v['data'], v['chld'] ? Object.keys(v['chld']).map(c => parseInt(c)) : (bodyRoot ? []: undefined)];
        if (v['chld']) {
          this.flatten(v['chld'], false);
        }
      }
    });
  }

  getNameWithArgs(uniqueId) {
    return uniqueId + ' ' +  this.methodLookup[this.nodes[uniqueId][0][0]] + ' ' + this.nodes[uniqueId][0][1];
  }

  getChildrenAsync(uniqueId) {
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    const body = (uniqueId === -1) ? [] : [uniqueId];
    return http.post(this.url, body).then(
      resp => {
        console.log('body: ', body, 'response: ', resp);
        this.flatten(resp, true);
        Object.assign(this.methodLookup, resp['method_lookup']);
        if (uniqueId === -1) {
          this.nodes[uniqueId] = [null, Object.keys(resp).filter((k) => k !== 'method_lookup').map(k => parseInt(k))];
        }
        return this.nodes[uniqueId][1];
      }
    );

  }

  getSampleCount(uniqueId) {
    return this.nodes[uniqueId][0][2];
  }

  getChildren(uniqueId) {
    if (this.nodes[uniqueId]) {
      return this.nodes[uniqueId][1];
    }else{
      return [];
    }
  }
}
