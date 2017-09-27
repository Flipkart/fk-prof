import React, {Component, PropTypes} from 'react';
import {connect} from 'react-redux';
import Loader from '../LoaderComponent/LoaderComponent';

import {
  policyAction,
  policyScheduleChangeAction,
  policyDescriptionChangeAction,
  policyWorkChangeAction
} from "../../actions/PolicyActions";

import {FETCH_POLICY, CREATE_POLICY, UPDATE_POLICY} from "../../actions/PolicyActions";

class PolicyComponent extends Component {

  constructor(props) {
    super(props);
    this.handleScheduleChange = this.handleScheduleChange.bind(this);
    this.handleDescriptionChange = this.handleDescriptionChange.bind(this);
    this.handleSubmitClick = this.handleSubmitClick.bind(this);
    this.handleWorkChange = this.handleWorkChange.bind(this);
    this.isCreateView = this.isCreateView.bind(this);
    this.isUpdateView = this.isUpdateView.bind(this);
    this.showPromptMsg = this.showPromptMsg.bind(this);
    this.makeRequest = this.makeRequest.bind(this);
  }

  componentDidMount() {
    this.makeRequest(FETCH_POLICY);
  }

  componentDidUpdate(prevProps) {
    if (prevProps.proc !== this.props.proc) {
      this.makeRequest(FETCH_POLICY);
    }
  }

  handleScheduleChange(e) {
    const target = e.target;
    const id = target.id;
    let schedule = {...this.props.versionedPolicyDetails.data.policyDetails.policy.schedule};
    schedule[id] = target.required ? target.value : (target.value === ''? undefined : target.value);   //assigning undefined removes the field from the payload in redux store for optional input
    this.props.policyScheduleChangeAction(schedule);
  }

  handleDescriptionChange(e) {
    const value = e.target.value;
    this.props.policyDescriptionChangeAction(value);
  }

  handleWorkChange(e) {
    const prevWorks = this.props.versionedPolicyDetails.data.policyDetails.policy.work;
    const target = e.target;
    const value = target.value;
    const wType = target.name;
    const [wKey, attribute] = target.id.split('_');

    const worksWithWType = prevWorks.filter((work) => {
      return work.wType === wType;
    });

    let w = {};
    if (worksWithWType.length === 0) {
      w = {
        wType,
        [wKey]: {
          [attribute]: value
        }
      }
    } else {
      w = {
        wType,
        [wKey]: {...worksWithWType[0][wKey], [attribute]: value}
      }
    }
    const works = [...prevWorks.filter((w) => {
      return w.wType !== wType
    })];
    works.push(w);
    this.props.policyWorkChangeAction(works);
  }

  handleSubmitClick() {
    if ((this.props.versionedPolicyDetails.reqType === CREATE_POLICY || this.props.versionedPolicyDetails.reqType === UPDATE_POLICY) && this.props.versionedPolicyDetails.asyncStatus === 'PENDING') {
      this.showPromptMsg('Please wait, your previous policy change is still pending');
    } else {
      if (document.querySelectorAll(":invalid").length !== 0) {
        this.showPromptMsg('Please provide appropriate values to the fields marked in red');
      } else {
        if (this.isCreateView()) {
          this.makeRequest(CREATE_POLICY);
        } else if (this.isUpdateView()) {
          this.makeRequest(UPDATE_POLICY);
        }
      }
    }
  }

  render() {
    if (!this.props.versionedPolicyDetails) return null;
    if (this.props.versionedPolicyDetails.asyncStatus === 'PENDING') {
      return (
        <div>
          <h3 style={{textAlign: 'center'}}>Please wait, coming right up!</h3>
          <Loader/>
        </div>
      );
    }

    if (this.isCreateView() || this.isUpdateView()) {
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
            className="mdl-typography--headline mdl-typography--font-thin mdl-color-text--accent mdl-cell--12-col">There
            was a problem loading the page, please try later.
          </div>
        </div>
        <div id="policy-submit" className="mdl-js-snackbar mdl-snackbar">
          <div className="mdl-snackbar__text"/>
          <button className="mdl-snackbar__action" type="button"/>
        </div>
      </div>
    );
  }

  getDisplayDetails() {
    if (this.isUpdateView()) {
      const createdDate = new Date(this.props.versionedPolicyDetails.data.policyDetails.createdAt);
      const modifiedDate = new Date(this.props.versionedPolicyDetails.data.policyDetails.modifiedAt);
      const createdString = createdDate.toDateString() + ', ' + createdDate.toLocaleTimeString();
      const modifiedString = modifiedDate.toDateString() + ', ' + modifiedDate.toLocaleTimeString();
      return (
        <div className="mdl-grid mdl-cell--12-col" style={{borderBottom: '1px solid rgba(0,0,0,.13)'}}>
          <div className="mdl-grid mdl-cell--12-col">
            <div className="mdl-cell--2-col"><span
              className="mdl-color-text--primary mdl-typography--caption">Version:  </span>{this.props.versionedPolicyDetails.data.version}
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
               onMouseOut={(e) => e.target.innerText = 'help_outline'}
               onMouseOver={(e) => e.target.innerText = 'help'}>help_outline</i>
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
          <input className="mdl-textfield__input" type="text" pattern="^0*([6-8][0-9]|9[0-9]|[1-8][0-9]{2}|9[0-5][0-9]|960)$" id="duration"
                 onChange={this.handleScheduleChange}
                 value={this.props.versionedPolicyDetails.data.policyDetails.policy.schedule.duration}
                 required/>
          <label className="mdl-textfield__label" htmlFor="duration">Duration (secs)</label>
          <span className="mdl-textfield__error">Duration must be between 60-960 secs</span>
          <div className="mdl-tooltip mdl-tooltip--large" htmlFor="duration">
            How long profiling should run on a single host in a 20 min window?
          </div>
        </div>
        <div className="mdl-layout-spacer"/>
        <div className="mdl-cell--4-col mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
          <input className="mdl-textfield__input" type="text" pattern="^0*(\d{1,2}|100)$" id="pgCovPct"
                 onChange={this.handleScheduleChange}
                 value={this.props.versionedPolicyDetails.data.policyDetails.policy.schedule.pgCovPct}
                 required/>
          <label className="mdl-textfield__label" htmlFor="pgCovPct">Coverage Percentage</label>
          <span className="mdl-textfield__error">Coverage % must be between 0-100</span>
          <div className="mdl-tooltip  mdl-tooltip--large" htmlFor="pgCovPct">How many hosts should be profiled in a
            20
            min window as a
            percentage of total recorder-enabled hosts in your cluster?
          </div>
        </div>
        <div className="mdl-layout-spacer"/>
        <div className="mdl-cell--4-col mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
          <input className="mdl-textfield__input" type="text" pattern="^0*([1-9]|\d{2,4}|10000)$" id="minHealthy"
                 onChange={this.handleScheduleChange}
                 value={this.props.versionedPolicyDetails.data.policyDetails.policy.schedule.minHealthy}/>
          <label className="mdl-textfield__label" htmlFor="minHealthy">Min Healthy (optional)</label>
          <span className="mdl-textfield__error">Min healthy recorders must be between 1-10000</span>
          <div className="mdl-tooltip mdl-tooltip--large" htmlFor="minHealthy">
            Profiling will stop if number of recorder-enabled healthy instances is less than this number. Not applicable if no value provided.
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
                  onChange={this.handleDescriptionChange}
                  value={this.props.versionedPolicyDetails.data.policyDetails.policy.description}
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
    const workArray = this.props.versionedPolicyDetails.data.policyDetails.policy.work;
    const cpu_sample_work = workArray.some((w) => w.wType === "cpu_sample_work") ? workArray.filter((w) => w.wType === "cpu_sample_work")[0].cpuSample : " ";
    return (<div className="mdl-grid mdl-cell--12-col" style={{borderBottom: '1px solid rgba(0,0,0,.13)'}}>
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-typography--headline mdl-typography--font-thin mdl-cell--12-col">Profiling Types
          <a href="http://fcp.fkinternal.com/#/docs/fkprof/latest/policy.md" target="_blank"
             rel="noopener noreferrer">
            <i className="material-icons" style={{fontSize: "16px", verticalAlign: 'top'}}
               onMouseOut={(e) => e.target.innerText = 'help_outline'}
               onMouseOver={(e) => e.target.innerText = 'help'}>help_outline</i>
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
            <input name="cpu_sample_work" className="mdl-textfield__input" type="text" pattern="^0*([5-8][0-9]|9[0-9]|100)$"
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
            <input name="cpu_sample_work" className="mdl-textfield__input" type="text" pattern="^0*([1-9]|\d{2,3})$"
                   id={"cpuSample_maxFrames"}
                   onChange={this.handleWorkChange} value={cpu_sample_work.maxFrames} required/>
            <label className="mdl-textfield__label" htmlFor="cpuSample_maxFrames">Max Frames</label>
            <span className="mdl-textfield__error">Max Frames should be between 1-999</span>
            <div className="mdl-tooltip mdl-tooltip--large" htmlFor="cpuSample_maxFrames">
              How deep can your application&#39;s stack-trace frames be? Deeper stack-traces will get snipped at this
              depth
            </div>
          </div>
          <div className="mdl-layout-spacer"/>
        </div>
      </div>
    </div>);
  }

  getSubmit() {
    const actionText = this.isCreateView() ? 'Create Policy' : 'Update Policy';
    const notifColor = ' mdl-color-text--primary';
    return (
      <div className="mdl-grid mdl-cell--12-col">
        <div className="mdl-grid mdl-cell--12-col">
          <div
            className={"mdl-typography--headline mdl-typography--font-thin mdl-cell--10-col mdl-cell--6-col-tablet mdl-cell--2-col-phone mdl-cell--middle" + notifColor}
          >{actionText}
          </div>
          <div className="mdl-layout-spacer"/>
          <div className="mdl-cell mdl-cell--2-col mdl-cell--2-col-tablet mdl-cell--middle"
               style={{textAlign: 'right'}}>
            <button className="mdl-button mdl-js-button mdl-button--colored mdl-button--raised mdl-js-ripple-effect"
                    style={{margin: '0 auto'}}
                    onClick={this.handleSubmitClick}>
              Submit
            </button>
          </div>
        </div>
      </div>);
  }

  showPromptMsg = (msg) => {
    componentHandler.upgradeDom(); // eslint-disable-line  //To apply mdl JS behaviours on components loaded later https://github.com/google/material-design-lite/issues/5081
    document.querySelector('#policy-submit').MaterialSnackbar.showSnackbar({message: msg, timeout: 3500});
  };

  makeRequest(reqType) {
    this.props.policyAction(reqType, this.props.app, this.props.cluster, this.props.proc, this.props.versionedPolicyDetails.data).then(this.showPromptMsg, this.showPromptMsg);
  }

  isCreateView() {
    return (( this.props.versionedPolicyDetails.reqType === FETCH_POLICY && this.props.versionedPolicyDetails.asyncStatus === 'ERROR' && this.props.versionedPolicyDetails.error.status === 404)
      || (this.props.versionedPolicyDetails.reqType === CREATE_POLICY && this.props.versionedPolicyDetails.asyncStatus === 'ERROR'));
  }

  isUpdateView() {
    return ((this.props.versionedPolicyDetails.reqType === UPDATE_POLICY)
      || ((this.props.versionedPolicyDetails.reqType === FETCH_POLICY || this.props.versionedPolicyDetails.reqType === CREATE_POLICY ) && this.props.versionedPolicyDetails.asyncStatus === 'SUCCESS'));
  }

}

const mapStateToProps = state => ({
  versionedPolicyDetails: state.versionedPolicyDetails || {},
});
const mapDispatchToProps = dispatch => ({
  policyAction: (reqType, app, cluster, proc, versionedPolicyDetails) => dispatch(policyAction(reqType, app, cluster, proc, versionedPolicyDetails)),
  policyScheduleChangeAction: schedule => dispatch(policyScheduleChangeAction(schedule)),
  policyDescriptionChangeAction: description => dispatch(policyDescriptionChangeAction(description)),
  policyWorkChangeAction: work => dispatch(policyWorkChangeAction(work)),
});

PolicyComponent.propTypes = {
  app: PropTypes.string,
  cluster: PropTypes.string,
  proc: PropTypes.string,
  versionedPolicyDetails: PropTypes.object.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(PolicyComponent);

