import React from "react";
import Loader from '../LoaderComponent/LoaderComponent';

import http from 'utils/http';

export default class PolicyComponent extends React.Component {

  constructor(props) {
    super(props);
    this.handleScheduleChange = this.handleScheduleChange.bind(this);
    this.handleDescriptionChange = this.handleDescriptionChange.bind(this);
    this.handleSubmitClick = this.handleSubmitClick.bind(this);
    this.handleWorkChange = this.handleWorkChange.bind(this);
    this.getPolicy = this.getPolicy.bind(this);
    this.postPolicy = this.postPolicy.bind(this);
    this.state = {
      query: {
        type: "GET",
        state: "INIT"
      },
      json: null,
      err: null,
      msg: "Failed to fetch the data. Please refresh or try again later."
    };
  }

  componentDidMount() {
    this.getPolicy();
  }

  componentDidUpdate(prevProps, prevState) {
    if (prevProps.proc !== this.props.proc) {
      this.getPolicy();
    }
    componentHandler.upgradeDom(); // eslint-disable-line  //To apply mdl JS behaviours on components loaded later https://github.com/google/material-design-lite/issues/5081
    if (prevState.query.state === 'PENDING' && this.state.query.state !== 'PENDING' && prevState.query.type === this.state.query.type) {
      let data = {};
      if (this.state.query.state === 'FAILURE' && this.state.query.type === 'GET') {
        data = {message: 'Failed to fetch data'}
      } else {
        switch (this.state.query.type) {
          case 'POST':
            data = (this.state.query.state === 'SUCCESS') ? {message: 'Policy created successfully'} : {message: 'Policy create failed, try again later'};
            break;
          case 'PUT':
            data = (this.state.query.state === 'SUCCESS') ? {message: 'Policy updated successfully'} : {message: 'Policy update failed, try again later'};
            break;
          case 'GET':
            data = (this.state.err.status === 404) ? {message: 'Policy not found, create one'} : {message: 'Policy found'};
            break;
          default:
            data = {message: 'Invalid request type'};
        }
      }
      document.querySelector('#policy-submit').MaterialSnackbar.showSnackbar(data);
    }
  }

  getPolicy() {
    this.setState({
      query: {
        type: "GET",
        state: "PENDING"
      }
    });
    const url = '/api/policy/' + this.props.app + '/' + this.props.cluster + '/' + this.props.proc;
    http.get(url).then(json => {
      this.setState({
        msg: "Update policy",
        query: {
          type: "GET",
          state: "SUCCESS"
        },
        json,
        err: {}
      })
    }).catch(err => {
      if (err.status === 404) {
        this.setState({
          msg: "Create policy",
          query: {
            type: "GET",
            state: "SUCCESS"
          },
          err,
          json: {
            version: -1,
            policyDetails: {
              createdAt: "",
              modifiedAt: "",
              modifiedBy: "Anonymous",
              policy: {
                description: "",
                schedule: {
                  duration: 120,
                  pgCovPct: 10,
                  after: 0
                },
                work: [{
                  wType: "cpu_sample_work",
                  cpuSample: {
                    frequency: 50,
                    maxFrames: 64
                  }
                }]
              }
            }
          }
        })
      } else {
        this.setState({
          query: {
            type: "GET",
            state: "FAILURE"
          },
          err,
        })
      }
    });
  }

  putPolicy() {
    this.setState({
      query: {
        type: "PUT",
        state: "PENDING"
      }
    });
    const url = '/api/policy/' + this.props.app + '/' + this.props.cluster + '/' + this.props.proc;
    http.put(url, this.state.json).then(json => {
      this.setState({
        query: {
          type: "PUT",
          state: "SUCCESS"
        },
        json,
        err: {},
        msg: "Update policy"
      })
    }).catch(err => {
      this.setState({
        query: {
          type: "PUT",
          state: "FAILURE"
        },
        err,
        msg: "There was a problem updating your policy, try again later."
      })
    });
  }

  postPolicy() {
    this.setState({
      query: {
        type: "POST",
        state: "PENDING"
      }
    });
    const url = '/api/policy/' + this.props.app + '/' + this.props.cluster + '/' + this.props.proc;
    http.post(url, this.state.json).then(json => {
      this.setState({
        query: {
          type: "POST",
          state: "SUCCESS"
        },
        json,
        err: {},
        msg: "Update policy"
      })
    }).catch(err => {
      this.setState({
        query: {
          type: "POST",
          state: "FAILURE"
        },
        err,
        msg: "There was a problem creating your policy, try again later"
      })
    });
  }

  handleScheduleChange(e) {
    const target = e.target;
    const value = parseInt(target.value);
    const id = target.id;
    const schedule = {...this.state.json.policyDetails.policy.schedule, [id]: value};
    this.setState((prevState) => ({
      json: {
        version: prevState.json.version,
        policyDetails: {...prevState.json.policyDetails, policy: {...prevState.json.policyDetails.policy, schedule}}
      },
    }));
  }

  handleDescriptionChange(e) {
    const value = e.target.value;
    this.setState((prevState) => ({
      json: {
        version: prevState.json.version,
        policyDetails: {
          ...prevState.json.policyDetails,
          policy: {...prevState.json.policyDetails.policy, description: value}
        }
      },
    }));
  }

  handleWorkChange(e) {
    const prevWorks = this.state.json.policyDetails.policy.work;
    const target = e.target;
    const value = parseInt(target.value);

    const wType = target.name;
    const [wKey, attribute] = target.id.split('_');

    const work = prevWorks.filter((work) => {
      return work.wType === wType;
    });

    let w = {};
    if (work.length === 0) {
      w = {
        wType,
        [wKey]: {
          [attribute]: value
        }
      }
    } else {
      w = {
        wType,
        [wKey]: {...work[0][wKey], [attribute]: value}
      }
    }
    if (Object.values(w[wKey]).every((el) => isNaN(el))) { //Remove work type element from work array if all of the attributes are empty
      this.setState((prevState) => ({
        json: {
          version: prevState.json.version,
          policyDetails: {
            ...prevState.json.policyDetails, policy: {
              ...prevState.json.policyDetails.policy, work: [...prevWorks.filter((w) => {
                return w.wType !== wType
              })]
            }
          },
        }
      }));
    } else {
      this.setState((prevState) => ({
          json: {
            version: prevState.json.version,
            policyDetails: {
              ...prevState.json.policyDetails, policy: {
                ...prevState.json.policyDetails.policy, work: [...prevWorks.filter((w) => {
                  return w.wType !== wType
                }), w]
              }
            },
          }
        })
      );
    }
  }

  handleSubmitClick(e) {
    if ((this.state.query.type === 'POST' || this.state.query.type === 'PUT') && this.state.query.state === 'PENDING') {
      this.setState({
        msg: "Please wait, your previous policy change is still pending"
      });
    } else {
      let data = {};
      if (document.querySelectorAll(":invalid").length !== 0) {
        data = {message: "Please provide appropriate values to the fields marked in red"};
      } else {
        if ((this.state.err.status === 404 && this.state.query.type === 'GET') || (this.state.query.type === 'POST' && this.state.query.state === 'FAILURE')) {
          this.postPolicy();
        } else {
          this.putPolicy();
        }
      }
    }
  }

  render() {
    if (!this.state.query) return null;
    if (this.state.query.type === 'GET' && this.state.query.state === 'PENDING') {
      return (
        <div>
          <h3 style={{textAlign: 'center'}}>Please wait, coming right up!</h3>
          <Loader/>
        </div>
      );
    }

    if ((this.state.query.type === 'GET' && this.state.query.state === 'SUCCESS') ||
      this.state.query.type === 'POST' || this.state.query.type === 'PUT') {
      return (
        <div className="mdl-grid mdl-grid--no-spacing mdl-cell--11-col mdl-shadow--3dp">
          {this.getDisplayDetails()}
          {this.getSchedule()}
          {this.getWork()}
          {this.getSubmit()}
          <div id="policy-submit" className="mdl-js-snackbar mdl-snackbar">
            <div className="mdl-snackbar__text"/>
            <button className="mdl-snackbar__action" type="button"/>
          </div>
        </div>
      );
    }
    return (
      <div className="mdl-grid mdl-grid--no-spacing mdl-cell--11-col mdl-shadow--3dp">
        <div className="mdl-grid mdl-cell--12-col">
          <div
            className="mdl-typography--headline mdl-typography--font-thin mdl-color-text--accent mdl-cell--12-col">{this.state.msg}</div>
        </div>
        <div id="policy-submit" className="mdl-js-snackbar mdl-snackbar">
          <div className="mdl-snackbar__text"/>
          <button className="mdl-snackbar__action" type="button"/>
        </div>
      </div>
    );
  }


  isCreateView() {
    return (this.state.err.status === 404 && this.state.query.type === 'GET') || (this.state.query.type === 'POST' && (this.state.query.state === 'FAILURE' || this.state.query.state === 'PENDING'));
  }

  getDisplayDetails() {
    if (this.isCreateView()) {
      return;
    } else {
      const createdDate = new Date(this.state.json.policyDetails.createdAt);
      const modifiedDate = new Date(this.state.json.policyDetails.modifiedAt);
      const createdString = createdDate.toDateString() + ', ' + createdDate.toLocaleTimeString();
      const modifiedString = modifiedDate.toDateString() + ', ' + modifiedDate.toLocaleTimeString();
      return (
        <div className="mdl-grid mdl-cell--12-col" style={{borderBottom: '1px solid rgba(0,0,0,.13)'}}>
          <div className="mdl-grid mdl-cell--12-col">
            <div className="mdl-cell--2-col"><span
              className="mdl-color-text--primary mdl-typography--caption">Version:  </span>{this.state.json.version}
            </div>
            <div className="mdl-layout-spacer"/>
            <div className="mdl-cell--4-col mdl-cell--8-col-tablet"><span
              className="mdl-color-text--primary mdl-typography--caption">Created at:  </span>{createdString}</div>
            <div className="mdl-layout-spacer"/>
            <div className="mdl-cell--4-col mdl-cell--8-col-tablet"><span
              className="mdl-color-text--primary mdl-typography--caption">Modified at:  </span>{modifiedString}</div>
            <div className="mdl-layout-spacer"/>
          </div>
        </div>);
    }
  }

  getSchedule() {
    return (<div className="mdl-grid mdl-cell--12-col" style={{borderBottom: '1px solid rgba(0,0,0,.13)'}}>
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-typography--headline mdl-typography--font-thin mdl-cell--12-col">Scheduling strategy
          <a href="http://fcp.fkinternal.com/#/docs/fkprof/latest/policy.md" target="_blank"
             rel="noopener noreferrer">
            <i className="material-icons" style={{fontSize: "16px", verticalAlign: 'top'}}
               onMouseOut={(e) => e.target.innerText = 'help_outline'} onMouseOver={(e) => e.target.innerText = 'help'}>help_outline</i>
          </a>
        </div>
      </div>
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-typography--caption mdl-color-text--grey mdl-cell--12-col">
          Every cluster-level profile generated by FkProf is an aggregation of multiple host-level profiles over a
          window.
          This window is set to be 20 minutes and is not configurable.
        </div>
      </div>
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-cell--4-col mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
          <input className="mdl-textfield__input" type="number" min="60" max="960" id="duration"
                 onChange={this.handleScheduleChange} value={this.state.json.policyDetails.policy.schedule.duration}
                 required/>
          <label className="mdl-textfield__label" htmlFor="duration">Duration (secs)</label>
          <span className="mdl-textfield__error">Duration must be between 60-960 secs</span>
          <div className="mdl-tooltip mdl-tooltip--large" htmlFor="duration">
            How long profiling should run on a single host in a 20 min window?
          </div>
        </div>
        <div className="mdl-layout-spacer"/>
        <div className="mdl-cell--4-col mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
          <input className="mdl-textfield__input" type="number" min="0" max="100" id="pgCovPct"
                 onChange={this.handleScheduleChange} value={this.state.json.policyDetails.policy.schedule.pgCovPct}
                 required/>
          <label className="mdl-textfield__label" htmlFor="pgCovPct">Coverage Percentage</label>
          <span className="mdl-textfield__error">Coverage % must be between 0-100</span>
          <div className="mdl-tooltip  mdl-tooltip--large" htmlFor="pgCovPct">How many hosts should be profiled in a 20
            min window as a
            percentage of total recorder-enabled hosts in your cluster?
          </div>
        </div>
        <div className="mdl-layout-spacer"/>
      </div>
      <div className="mdl-grid mdl-cell--12-col ">
        <div className="mdl-cell--12-col mdl-color-text--primary mdl-typography--caption"
             style={{marginBottom: '-16px'}}>Description
        </div>
        <div className="mdl-cell--12-col mdl-textfield mdl-js-textfield" style={{marginTop: '-8px'}}>
        <textarea className="mdl-textfield__input" type="text" id="description" rows="1"
                  onChange={this.handleDescriptionChange} value={this.state.json.policyDetails.policy.description}
                  required/>
          <label className="mdl-textfield__label" htmlFor="description"> Add commit message about the policy</label>
        </div>
        <div className="mdl-tooltip mdl-tooltip--large" htmlFor="description">
          How would you identify this policy change in future?
        </div>
      </div>
      <div className="mdl-layout-spacer"/>
    </div>);
  }

  getWork() {
    const workArray = this.state.json.policyDetails.policy.work;
    const cpu_sample_work = workArray.some((w) => w.wType === "cpu_sample_work") ? workArray.filter((w) => w.wType === "cpu_sample_work")[0].cpuSample : " ";
    return (<div className="mdl-grid mdl-cell--12-col" style={{borderBottom: '1px solid rgba(0,0,0,.13)'}}>
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-typography--headline mdl-typography--font-thin mdl-cell--12-col">Profiling Types
          <a href="http://fcp.fkinternal.com/#/docs/fkprof/latest/policy.md" target="_blank"
             rel="noopener noreferrer">
            <i className="material-icons" style={{fontSize: "16px", verticalAlign: 'top'}}
               onMouseOut={(e) => e.target.innerText = 'help_outline'} onMouseOver={(e) => e.target.innerText = 'help'}>help_outline</i>
          </a>
        </div>
      </div>
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-grid mdl-cell--12-col">
          <div className="mdl-typography--body-1 mdl-typography--font-thin mdl-color-text--primary mdl-cell--4-col">
            CPU Sampling
            <div className="mdl-typography--caption mdl-color-text--grey">
              Provides details of methods running on CPU
            </div>
          </div>
          <div className="mdl-layout-spacer"/>
          <div
            className="mdl-cell--4-col mdl-cell--7-col-tablet mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
            <input name="cpu_sample_work" className="mdl-textfield__input" type="number" min="50" max="100"
                   id={"cpuSample_frequency"}
                   onChange={this.handleWorkChange} value={cpu_sample_work.frequency} required/>
            <label className="mdl-textfield__label" htmlFor="cpuSample_frequency">Frequency (Hz)</label>
            <span className="mdl-textfield__error">Frequency must be between 50-100</span>
            <div className="mdl-tooltip mdl-tooltip--large" htmlFor="cpuSample_frequency">How frequently should your
              application
              be sampled? Higher the value, more insightful are the profiles but incurs more overhead
            </div>
          </div>
          <div className="mdl-layout-spacer"/>
          <div
            className="mdl-cell--4-col mdl-cell--7-col-tablet mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
            <input name="cpu_sample_work" className="mdl-textfield__input" type="number" min="1" max="999"
                   id={"cpuSample_maxFrames"}
                   onChange={this.handleWorkChange} value={cpu_sample_work.maxFrames} required/>
            <label className="mdl-textfield__label" htmlFor="cpuSample_maxFrames">Max Frames</label>
            <span className="mdl-textfield__error">Max Frames should be between 1-999</span>
            <div className="mdl-tooltip mdl-tooltip--large" htmlFor="cpuSample_maxFrames">
              How deep can your application's stack-trace frames be? Deeper stack-traces will get snipped at this depth
            </div>
          </div>
          <div className="mdl-layout-spacer"/>
        </div>
      </div>
    </div>);
  }

  getSubmit() {
    let buttonText = '';
    if (this.isCreateView()) {
      buttonText = 'CREATE';
    } else {
      buttonText = 'UPDATE';
    }
    let notifColor = ' mdl-color-text--primary';
    if (this.state.query.state === 'FAILURE') {
      notifColor = ' mdl-color-text--accent';
    }
    return (
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-grid mdl-cell--12-col">
          <div
            className={"mdl-typography--headline mdl-typography--font-thin mdl-cell--10-col mdl-cell--6-col-tablet mdl-cell--2-col-phone mdl-cell--middle" + notifColor}
          >{this.state.msg}</div>
          <div className="mdl-layout-spacer"/>
          <div className="mdl-cell mdl-cell--2-col mdl-cell--2-col-tablet mdl-cell--middle"
               style={{textAlign: 'right'}}>
            <button className="mdl-button mdl-js-button mdl-button--colored mdl-button--raised mdl-js-ripple-effect"
                    style={{margin: '0 auto'}}
                    onClick={this.handleSubmitClick}>
              {buttonText}
            </button>
          </div>
        </div>
      </div>);
  }

}
