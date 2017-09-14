import postWithRetryOnAccept from '../utils/httpHelper';

export default class HotMethodStore {
  constructor(url) {
    this.nodes = [];
    this.methodLookup = {};
    this.url = url;
  }

  deDup(root, topLevel, parent) {
    //input: {k: {data:{}, chld:{}},...]
    return Object.entries(root).reduce((accumNodes, [k, v]) => {
      if (k !== 'method_lookup') {
        let key = v['data'][0];      //method id
        if (!topLevel) {
          key = key + ':' + v['data'][1];       //add line number if this layer is top layer of hot-method tree
        }
        if (accumNodes[key]) {
          accumNodes[key][3].push([k, v, v['data'][2]]);        //[data, chld, parts]
          accumNodes[key][0][2] = accumNodes[key][0][2] + v['data'][2];       //adding up the sample count
        } else {
          const data = Array.from(v['data']);
          accumNodes[key] = [data, undefined, parent, [[k, v, data[2]]]];        //[data, chld, parent, parts]
        }
      }
      return accumNodes;
    }, {});
    //output: {name/name:line: [data*, chld*, part]} where parts = [[k, v]...]], data contains aggregated data of same name:line nodes, chld is undefined to be populated later
  }

  //handle new request prepare the data use parent if needed
  flatten(nodes, topLevel, bodyRoot, parent) {
    const deDupNodes = this.deDup(nodes, topLevel, parent);
    let curLayerIds = undefined;
    Object.entries(deDupNodes).forEach(([k, v]) => {
      this.nodes.push(v);
      const vIndex = this.nodes.length - 1;
      if (!curLayerIds) {
        curLayerIds = [];
      }
      curLayerIds.push(vIndex);
      //Try next layer for this aggregated/deDuped node
      const nextLayerNodes = {};
      if (!topLevel) {
        v[3].filter(([k, v, d]) => v['chld']).forEach(([k, v, d]) => {
          if (Object.keys(v['chld']).length === 1) {
            Object.values(v['chld'])[0]['data'][2] = d;
            Object.assign(nextLayerNodes, v['chld']);
          } else {
            console.error('hotMethod node should never happen more than one child/hotMethod parent, this has : ', v['chld']);
          }
        });
        bodyRoot = false;
      } else {
        v[3].forEach(([k, v]) => {
          Object.assign(nextLayerNodes, {[k]: v});
        });
        bodyRoot = true;
      }
      this.nodes[vIndex][1] = this.flatten(nextLayerNodes, false, bodyRoot, vIndex);
      if (!this.nodes[vIndex][1] && bodyRoot) {        //if no part has a child field and if this is the body's root then it is a leaf node. Also nodes at bodyRoot level are guaranteed to have their children in the response
        this.nodes[vIndex][1] = [];
      }
      if (this.nodes[vIndex][1] && this.nodes[vIndex].length === 4) {
        this.nodes[vIndex].pop();        //if chld is populated then remove parts
      }
    });
    return curLayerIds;
  }

  handleResponse(resp, uniqueId) {
    if (uniqueId === -1) {
      this.nodes[uniqueId] = [null, undefined, Object.entries(resp).filter(([k, v]) => k !== 'method_lookup'), undefined];
      this.nodes[uniqueId][1] = this.flatten(resp, uniqueId === -1, true, -1);
    } else {
      const nextLayerNodes = {};
      this.nodes[uniqueId][3].map(([k, v, d]) => [k, resp[k], d]).filter(([k, v, d]) => v['chld']).forEach(([k, v, d]) => {
        if (Object.keys(v['chld']).length === 1) {
          Object.values(v['chld'])[0]['data'][2] = d;
          Object.assign(nextLayerNodes, v['chld']);
        } else {
          console.error('hotMethod node should never happen more than one child/hotMethod parent, this has : ', v['chld']);
        }
      });
      this.nodes[uniqueId][1] = this.flatten(nextLayerNodes, uniqueId === -1, true, uniqueId);
    }
    if (!this.nodes[uniqueId][1]){     //because bodyRoot is true here, see flatten second last if block
      this.nodes[uniqueId][1] = [];
    }
    if (this.nodes[uniqueId][1] && this.nodes[uniqueId].length === 4) {
      this.nodes[uniqueId].pop();        //if chld is populated then remove parts
    }
    Object.entries(resp['method_lookup']).forEach(([k,v])=>{
      const splits = v.split(" ");
      if (splits.length === 2) {
        resp['method_lookup'][k] = splits;
      } else {
        resp['method_lookup'][k] = [v, ""];
      }
    });
    Object.assign(this.methodLookup, resp['method_lookup']);
    return this.nodes[uniqueId][1];
  }

  getChildrenAsync(uniqueId) {
    //if exists then return the children
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    //else fetch from api
    const body = (uniqueId === -1) ? [] : this.nodes[uniqueId][3].map(([k, v]) => parseInt(k));    //body contains the parts of the aggregated node at uniqueId
    return postWithRetryOnAccept(this.url, body, 5).then((resp) => this.handleResponse(resp, uniqueId), (err) => Promise.reject((err.response && (err.response.message || err.response.error)) || err));
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
    return uniqueId === -1;
  }
}
