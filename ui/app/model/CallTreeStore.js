import postWithRetryOnAccept from '../utils/httpHelper';

export default class CallTreeStore {
  constructor(url) {
    this.nodes = {};
    this.methodLookup = {};
    this.url = url;
  }

  flatten(respRoot, bodyRoot, parent) {
    //add sorting logic on Object.entries if any
    Object.entries(respRoot).forEach(([k, v]) => {
      this.nodes[parseInt(k)] = [v['d'], v['c'] ? Object.keys(v['c']).map(c => parseInt(c)) : (bodyRoot ? [] : undefined), parent];    //nodes at bodyRoot level are guaranteed to have their children in the response
      if (v['c']) {
        this.flatten(v['c'], false, parseInt(k));
      }
    });
  }

  handleResponse(resp, uniqueId) {
    this.flatten(resp, true, uniqueId === -1 ? uniqueId : this.nodes[uniqueId][2]);
    if (uniqueId === -1) {
      const rootKey = Object.keys(resp)[0]; // array should be of size 1
      const rootChld = resp[rootKey]['c'];
      delete resp[rootKey];                 // removing the root of the calltree
      Object.assign(resp, rootChld);
      this.nodes[uniqueId] = [null, Object.keys(resp).map(k => parseInt(k)), undefined];
    }
    return this.nodes[uniqueId][1];
  }

  getChildrenAsync(uniqueId) {
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    const body = (uniqueId === -1) ? [] : [uniqueId];
    return postWithRetryOnAccept(this.url, body, 5).then((resp) => {
      Object.entries(resp['method_lookup']).forEach(([k, v]) => {
        const splits = v.split(" ");
        resp['method_lookup'][k] = splits.length === 2 ? splits : [v, ""];
      });
      Object.assign(this.methodLookup, resp['method_lookup']); //Merging new method names from the response
      return this.handleResponse(resp['nodes'], uniqueId);
    }, (err) => Promise.reject((err.response && (err.response.message || err.response.error)) || err));
  }

  getMethodName(uniqueId, showLineNo) {
    return this.methodLookup[this.nodes[uniqueId][0][0]][0] + ((showLineNo) ? ': ' + this.nodes[uniqueId][0][1] : '');
  }

  getFullyQualifiedMethodName(uniqueId, showLineNo) {
    return this.methodLookup[this.nodes[uniqueId][0][0]].join(' ') + ((showLineNo) ? ': ' + this.nodes[uniqueId][0][1] : '');
  }

  getSampleCount(uniqueId) {
    return this.nodes[uniqueId][0][2];
  }

  getChildren(uniqueId) {
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return this.nodes[uniqueId][1];
    } else {
      return [];
    }
  }

  getParent(uniqueId) {
    return this.nodes[uniqueId][2];
  }

  isRoot(uniqueId) {
    return uniqueId === 0;
  }
}
