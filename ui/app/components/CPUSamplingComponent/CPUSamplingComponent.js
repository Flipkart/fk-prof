import React, { Component, PropTypes } from 'react';

import MethodTreeComponent from 'components/MethodTreeComponent';
import {  AutoSizer } from 'react-virtualized';
import Tabs from 'components/Tabs';
import styles from './CPUSamplingComponent.css';

export class CPUSamplingComponent extends Component {


  render () {
    const { app, cluster, proc, fullScreen, profileStart } = this.props.location.query;
    const { traceName } = this.props.params;

    return (
      <div>
        {!fullScreen && (
          <div style={{ position: 'relative' }}>
            <a
              href={`/work-type/cpu_sample_work/${traceName}?app=${app}&cluster=${cluster}&proc=${proc}&profileStart=${profileStart}&workType=cpu_sample_work&fullScreen=true`}
              target="_blank"
              rel="noopener noreferrer"
              style={{ position: 'absolute', right: 10, top: 20, zIndex: 1 }}
            >
              <i
                className="material-icons"
              >launch</i>
            </a>
          </div>
        )}
        {fullScreen && (
          <div className={styles.card} style={{ background: '#C5CAE9' }}>
            <div className="mdl-grid">
              <div className="mdl-cell mdl-cell--3-col">
                <div className={styles.label}>App</div>
                <strong className={styles.bold}>{app}</strong>
              </div>
              <div className="mdl-cell mdl-cell--3-col">
                <div className={styles.label}>Cluster</div>
                <strong className={styles.bold}>{cluster}</strong>
              </div>
              <div className="mdl-cell mdl-cell--3-col">
                <div className={styles.label}>Proc</div>
                <strong className={styles.bold}>{proc}</strong>
              </div>
              <div className="mdl-cell mdl-cell--3-col">
                <div className={styles.label}>Trace Name</div>
                <strong className={styles.bold}>{traceName} (CPU Sampling)</strong>
              </div>
            </div>
          </div>
        )}
        <Tabs>
          <div>
            <div>Hot Methods</div>
            <MethodTreeComponent traceName={this.props.params.traceName} nextNodesAccessorField={"parent"} filterKey={null}/>
          </div>
          {/*<div>*/}
            {/*<div>Call Tree</div>*/}
            {/*<MethodTreeComponent traceName={this.props.params.traceName}/>*/}
          {/*</div>*/}
        </Tabs>
      </div>
    );
  }
}

CPUSamplingComponent.propTypes = {
  params: PropTypes.shape({
    traceName: PropTypes.string.isRequired,
  }),
  location: PropTypes.shape({
    query: PropTypes.shape({
      app: PropTypes.string,
      cluster: PropTypes.string,
      proc: PropTypes.string,
      workType: PropTypes.string,
      profileStart: PropTypes.string,
      selectedWorkType: PropTypes.string,
      profileDuration: PropTypes.string
    }),
  }),
};


export default CPUSamplingComponent;
